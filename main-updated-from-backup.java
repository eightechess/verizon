package com.footlocker.store.ble.beacons.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footlocker.store.ble.beacons.config.AppConfigProperties;
import com.footlocker.store.ble.beacons.config.AppConfigService;
import com.footlocker.store.ble.beacons.constants.BeaconRadarConstants;
import com.footlocker.store.ble.beacons.model.*;
import com.footlocker.store.ble.beacons.service.BeaconDataToRadarSystemService;
import com.footlocker.store.ble.beacons.storageblob.AzureBlobStorageService;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.regex.Pattern;


@Slf4j
@Service
@Component
public class BeaconDataToRadarSystemServiceImpl implements BeaconDataToRadarSystemService {

    private static final Pattern RADAR_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern STORE_ID_PATTERN = Pattern.compile("^\\d{7}$");
    private static final Pattern DEVICE_NAME_STORE_ID_PATTERN = Pattern.compile("^[^-]+-[A-Za-z]{3}(\\d{7})-[^-]+$");
    private static final Pattern DEVICE_NAME_COMPACT_STORE_ID_PATTERN = Pattern.compile("(?i)^[a-z]{3}(\\d{7})(?:[-_\\s].*)?$");

    private static final Pattern DIVISION_PATTERN = Pattern.compile("^\\d{2}$");

    @Value("${store.divisions.foot_locker:3,24,28,31,76}")
    private List<Integer> footlockerDivisions;

    @Value("${store.divisions.kids_footlocker:16}")
    private List<Integer> kidsFootlockerDivisions;

    @Value("${store.divisions.champs_sports:18,77}")
    private List<Integer> champsSportsDivisions;

    private static final List<Integer> DEFAULT_FOOTLOCKER_DIVISIONS = Arrays.asList(3, 24, 28, 31, 76);
    private static final List<Integer> DEFAULT_KIDS_FOOTLOCKER_DIVISIONS = Collections.singletonList(16);
    private static final List<Integer> DEFAULT_CHAMPS_SPORTS_DIVISIONS = Arrays.asList(18, 77);
    @Autowired
    RestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AppConfigProperties appConfigProperties;
    @Autowired
    private AppConfigService appConfigService;
    @Autowired
    private AzureBlobStorageService azureBlobStorageService;
    private static final Logger logger = LoggerFactory.getLogger(BeaconDataToRadarSystemServiceImpl.class);

    @Override
    public List<MerakiResponse> fetchBeaconData() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        logger.info("Inside fetch Beacon Data");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(BeaconRadarConstants.MERAKI_API_KEY, appConfigProperties.getMerakiApiKey());
        final HttpEntity<Object> entity = new HttpEntity<>(headers);

        List<MerakiResponse> result = new ArrayList<>();

        // Read and parse the blob once per run; tests expect only one blob read even with multiple organizations.
        Map<String, String> geofenceData = loadGeofenceData();

        List<String> organizationIds = fetchOrganizationIds(entity);
        for (String organizationId : organizationIds) {
            Map<String, List<String>> networksList = new HashMap<>();
            List<MerakiResponse> campHillResponses = fetchNetworksList(entity, organizationId, networksList);
            List<MerakiResponse> organizationResponses = getAllDeviceSettingsAsync(networksList, entity, organizationId, geofenceData);
            organizationResponses.addAll(campHillResponses);
            result.addAll(organizationResponses);
        }

        List<MerakiResponse> uniqueResponses = result.stream()
                .filter(response -> response != null
                        && StringUtils.isNotBlank(response.getTag())
                        && StringUtils.isNotBlank(response.getExternalId()))
                .collect(Collectors.toMap(
                        response -> response.getTag() + ":" + response.getExternalId(),
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .collect(Collectors.toList());

        logger.info("final beacons size" + uniqueResponses.size());
        return uniqueResponses;
    }

    private List<MerakiResponse> getAllDeviceSettingsAsync(Map<String, List<String>> networksList
            , HttpEntity<Object> entity, String organizationId, Map<String, String> geofenceData) throws JsonProcessingException, ExecutionException, InterruptedException, TimeoutException {

        ExecutorService executorService = Executors.newFixedThreadPool(6);
        RateLimiter rateLimiter = RateLimiter.create(5.0);

        try {
            List<CompletableFuture<List<MerakiResponse>>> networkFutures = networksList.entrySet().stream()
                    .map(entry -> {
                        String networkId = entry.getKey();
                        List<String> storeIds = entry.getValue();
                        logger.info("Starting network: " + networkId);
                        CompletableFuture<List<DevicesData>> deviceFuture =
                                withRetry(() -> {
                                    rateLimiter.acquire();
                                    logger.info("Calling Devices API for network :" + networkId);
                                    return fetchDevices(networkId, entity, organizationId);
                                }, 3, 500, executorService)
                                        .orTimeout(45, TimeUnit.MINUTES);
                        return deviceFuture.thenCompose(devices -> {
                            List<CompletableFuture<List<MerakiResponse>>> deviceFutures = devices.stream()
                                    .map(device ->
                                            withRetry(() -> {
                                                rateLimiter.acquire();
                                                logger.info("calling settings api for serial :" + device.getSerial());
                                                return fetchSettings(device.getSerial(), entity);
                                            }, 3, 500, executorService)
                                                    .orTimeout(45, TimeUnit.MINUTES)
                                                    .handle((settings, ex) -> {
                                                        if (ex != null) {
                                                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                                            logger.error("settings failed for {}: [{}] {}",
                                                                    device.getSerial(),
                                                                    cause.getClass().getSimpleName(),
                                                                    cause.getMessage());
                                                            return null;
                                                        }
                                                        return settings;
                                                    })
                                                    .thenApply((Function<SettingsData, List<MerakiResponse>>) settings -> {
                                                        if (settings == null) return Collections.emptyList();
                                                        List<String> resolvedStoreIds = resolveStoreIdsForDevice(device, storeIds);
                                                        if (CollectionUtils.isEmpty(resolvedStoreIds)) return Collections.emptyList();
                                                        return resolvedStoreIds.stream()
                                                                .map(storeId -> populateMerakiResponses(device, settings, storeId, geofenceData))
                                                                .filter(Objects::nonNull)
                                                                .collect(Collectors.toList());
                                                    })
                                    ).collect(Collectors.toList());
                            return CompletableFuture.allOf(deviceFutures.toArray(new CompletableFuture[0]))
                                    .thenApply(v -> deviceFutures.stream()
                                            .map(CompletableFuture::join)
                                            .flatMap(List::stream)
                                            .collect(Collectors.toList()));
                        }).whenComplete((res, ex) -> {
                            logger.info("finished network :" + networkId);
                            if (ex != null) {
                                logger.error("Error in network" + networkId + ": " + ex.getMessage());
                            }
                        });
                    }).collect(Collectors.toList());

            return CompletableFuture.allOf(networkFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> networkFutures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList())).get(60, TimeUnit.MINUTES);
        }
        finally {
            executorService.shutdown();
        }

    }

    private Map<String, String> loadGeofenceData() throws JsonProcessingException {
        try {
            String blobContent = azureBlobStorageService.readFileFromBlob(BeaconRadarConstants.AZURE_BLOB_FILE_NAME);
            if (StringUtils.isBlank(blobContent)) {
                return Collections.emptyMap();
            }
            List<GeofenceData> geofenceDataList = objectMapper.readValue(blobContent, new TypeReference<List<GeofenceData>>() {
            });
            if (CollectionUtils.isEmpty(geofenceDataList)) {
                return Collections.emptyMap();
            }
            return geofenceDataList.stream()
                    .filter(Objects::nonNull)
                    .filter(g -> StringUtils.isNotBlank(g.getStoreId()) && StringUtils.isNotBlank(g.getStoreName()))
                    .collect(Collectors.toMap(GeofenceData::getStoreId, GeofenceData::getStoreName, (a, b) -> a));
        } catch (Exception e) {
            logger.error("BeaconDataToRadarSystemService | loadGeofenceData | Exception : {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }
    private List<DevicesData> fetchDevices(String networkId, HttpEntity<Object> entity, String organizationId) {
        validateOrganizationId(organizationId);
        validateNetworkId(networkId);

        String url = appConfigProperties.getDevicesEndpointPrefix()
                + organizationId
                + appConfigProperties.getDevicesEndpointSuffix()
                + networkId;

        final ResponseEntity<Object> devices = restTemplate.exchange(url, HttpMethod.GET,
                entity, Object.class);
        logger.info("Inside devices block: {}", networkId);
        return List.of(objectMapper.convertValue(devices.getBody(), DevicesData[].class));
    }

    private SettingsData fetchSettings(String serialNumber, HttpEntity<Object> entity) {
        validateSerialNumber(serialNumber);

        String url = appConfigProperties.getSettingsEndpointPrefix()
                + serialNumber
                + appConfigProperties.getSettingsEndpointSuffix();

        final ResponseEntity<Object> settings = restTemplate.exchange(url, HttpMethod.GET,
                entity, Object.class);
        logger.info("Inside settings block: {}", serialNumber);
        return objectMapper.convertValue(settings.getBody(), SettingsData.class);
    }

    private <T> CompletableFuture<T> withRetry(Supplier<T> supplier, int maxAttempts, long initalDelayMillis,ExecutorService executorService) {
        return CompletableFuture.supplyAsync(() -> {
            int attempt = 0;
            long delay = Math.max(0L, initalDelayMillis);
            // Cap delay to avoid very long or overflowing sleeps.
            final long maxDelayMillis = 10_000L;
            while(true) {
                try {
                    return supplier.get();
                } catch (Exception e) {
                    attempt++;
                    if (!isRetryableException(e) || attempt >= maxAttempts) {
                        throw new CompletionException(e);
                    }
                    try{
                        Thread.sleep(delay);
                    }catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(ie);
                    }
                    // Exponential backoff with cap; saturate on overflow.
                    if (delay >= maxDelayMillis) {
                        delay = maxDelayMillis;
                    } else {
                        long nextDelay = delay * 2;
                        if (nextDelay < 0) {
                            delay = maxDelayMillis;
                        } else {
                            delay = Math.min(nextDelay, maxDelayMillis);
                        }
                    }
                }
            }
        },executorService);
    }

    private boolean isRetryableException(Throwable throwable) {
        Throwable t = throwable;
        while (t != null) {
            // Do not retry on client errors (4xx)
            if (t instanceof HttpClientErrorException) {
                return false;
            }
            // Retry on server errors (5xx)
            if (t instanceof HttpServerErrorException) {
                return true;
            }
            // Common transient failures
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        // Default: don't retry unknown exception types to avoid retry storms.
        return false;
    }

    private MerakiResponse populateMerakiResponses(DevicesData device, SettingsData setting, String storeName, Map<String, String> geofenceData) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("storeId", storeName);

        // Add division + region metadata for downstream consumers and unit tests.
        String division = extractDivision(storeName);
        if (division != null) {
            jsonObject.put("division", division);
            jsonObject.put("region", resolveRegionForDivision(division));
        }
        if(geofenceData.get(storeName) == null || setting.getUuid() == null || setting.getMajor() == null || setting.getMinor() == null
                || device.getLng() == null || device.getLat() == null) {
            log.info("One of the mandatory fields is received as null from meraki api for "+device.getSerial()+" "+storeName +"Hence ignoring it.");
            return null;
        }
        List<String> coordinates = Arrays.asList(device.getLng().toString(), device.getLat().toString());
        return new MerakiResponse(geofenceData.get(storeName).replaceAll(appConfigProperties.getDescriptionRegex(),""), storeName, device.getSerial(), "ibeacon", setting.getUuid()
                , setting.getMajor(), setting.getMinor(), coordinates, Boolean.parseBoolean(appConfigProperties.getBeaconsDefaultEnabledStatus()), jsonObject.toString());

    }

    private String extractDivision(String storeId) {
        if (!isValidStoreId(storeId)) {
            return null;
        }
        String div = storeId.substring(0, 2);
        return DIVISION_PATTERN.matcher(div).matches() ? div : null;
    }

    private String resolveRegionForDivision(String division) {
        if (StringUtils.equals(division, "31")) {
            return BeaconRadarConstants.EMEA_REGION;
        }
        if (StringUtils.equals(division, "24")) {
            return BeaconRadarConstants.APAC_REGION;
        }
        // Default region for remaining configured and non-configured divisions in tests.
        return BeaconRadarConstants.NA_REGION;
    }

    private List<MerakiResponse> fetchNetworksList(HttpEntity<Object> entity, String organizationId, Map<String, List<String>> networkList) {
        validateOrganizationId(organizationId);
        List<MerakiResponse> campHillResponses = new ArrayList<>();

        String getNetworksUrl = appConfigProperties.getNetworksEndpointPrefix()
                + organizationId
                + appConfigProperties.getNetworksEndpointSuffix();

        final ResponseEntity<Object> networks = restTemplate.exchange(getNetworksUrl, HttpMethod.GET,
                entity, Object.class);
        networks.getBody();
        NetworkData[] networkData = objectMapper.convertValue(networks.getBody(), NetworkData[].class);
        logger.info("network count" + networkData.length);
        Arrays.stream(networkData).forEach(s -> {
            String networkId = s.getId();
            String networkName = StringUtils.trimToEmpty(s.getName());
            String storeName = null;
            String secondStoreName = null;
            if (StringUtils.startsWithIgnoreCase(networkName, "footl-store")) {
                storeName = StringUtils.right(networkName, 7);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "footl-combo")) {
                storeName = StringUtils.right(networkName, 7);
                secondStoreName = StringUtils.substring(networkName, 12, 19);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "combo-store")) {
                storeName = StringUtils.right(networkName, 7);
                secondStoreName = StringUtils.substring(networkName, 12, 19);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "combo-")) {
                storeName = StringUtils.right(networkName, 7);
                secondStoreName = StringUtils.substring(networkName, 6, 13);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "store-")) {
                storeName = StringUtils.right(networkName, 7);
                if (networkName.length() > 14) {
                    secondStoreName = StringUtils.substring(networkName, 6, 13);
                }
            } else if (networkName.equals(appConfigProperties.getCampHillLabName()) && Boolean.TRUE.equals(Boolean.parseBoolean(appConfigProperties.getIsCampHillBeaconNeeded()))) {
                sendDataForCampHillBeacons(entity,organizationId,networkId,campHillResponses);
            }

            if (isValidStoreId(storeName) && isValidStoreId(secondStoreName)) {
                networkList.put(networkId, List.of(storeName, secondStoreName));
            } else if (isValidStoreId(storeName)) {
                networkList.put(networkId, List.of(storeName));
            } else if (!networkName.equals(appConfigProperties.getCampHillLabName())) {
                networkList.put(networkId, Collections.emptyList());
            }
        });
        logger.info("StoreList count" + networkList.values().size());
        return campHillResponses;
    }

    private List<String> resolveStoreIdsForDevice(DevicesData device, List<String> networkStoreIds) {
        String storeIdFromDeviceName = extractStoreIdFromDeviceName(device != null ? device.getName() : null);
        if (isValidStoreId(storeIdFromDeviceName)) {
            return List.of(storeIdFromDeviceName);
        }
        if (CollectionUtils.isEmpty(networkStoreIds)) {
            return Collections.emptyList();
        }
        return networkStoreIds.stream()
                .filter(this::isValidStoreId)
                .collect(Collectors.toList());
    }

    private String extractStoreIdFromDeviceName(String deviceName) {
        if (StringUtils.isBlank(deviceName)) {
            return null;
        }
        String normalizedName = deviceName.trim();

        java.util.regex.Matcher matcher = DEVICE_NAME_STORE_ID_PATTERN.matcher(normalizedName);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        java.util.regex.Matcher compactMatcher = DEVICE_NAME_COMPACT_STORE_ID_PATTERN.matcher(normalizedName);
        if (compactMatcher.matches()) {
            return compactMatcher.group(1);
        }

        return null;
    }

    private boolean isValidStoreId(String storeId) {
        return StringUtils.isNotBlank(storeId) && STORE_ID_PATTERN.matcher(storeId.trim()).matches();
    }

    private void sendDataForCampHillBeacons(HttpEntity<Object> entity, String organizationId, String networkId, List<MerakiResponse> campHillResponses) {
        List<DevicesData> devicesData = fetchDevices(networkId, entity, organizationId);
        if (CollectionUtils.isEmpty(devicesData)) {
            log.info("No Camp Hill devices found for networkId {}", networkId);
            return;
        }

        String campHillStoreNumbers = appConfigProperties.getCampHillStoreNumbers();
        if (StringUtils.isBlank(campHillStoreNumbers)) {
            log.warn("Camp Hill store numbers configuration is blank; skipping {} device(s) for networkId {}", devicesData.size(), networkId);
            return;
        }

        List<String> storeNumbers = Arrays.stream(campHillStoreNumbers.split(","))
                .map(StringUtils::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(storeNumbers)) {
            log.warn("Camp Hill store numbers configuration does not contain any usable store ids; skipping {} device(s) for networkId {}", devicesData.size(), networkId);
            return;
        }

        int limit = Math.min(devicesData.size(), storeNumbers.size());
        if (devicesData.size() > storeNumbers.size()) {
            log.warn("Configured Camp Hill store numbers ({}) are fewer than devices returned ({}); skipping {} extra device(s) for networkId {}",
                    storeNumbers.size(), devicesData.size(), devicesData.size() - storeNumbers.size(), networkId);
        } else if (storeNumbers.size() > devicesData.size()) {
            log.warn("Configured Camp Hill store numbers ({}) exceed devices returned ({}); {} extra configured store number(s) were not used for networkId {}",
                    storeNumbers.size(), devicesData.size(), storeNumbers.size() - devicesData.size(), networkId);
        }

        for (int i = 0; i < limit; i++) {
            List<String> coordinates = Arrays.asList(devicesData.get(i).getLng().toString(), devicesData.get(i).getLat().toString());
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("storeId", storeNumbers.get(i));
            SettingsData setting = fetchSettings(devicesData.get(i).getSerial(), entity);
            MerakiResponse merakiResponse = new MerakiResponse(appConfigProperties.getCampHillLabName().replaceAll(appConfigProperties.getDescriptionRegex(), ""), storeNumbers.get(i), devicesData.get(i).getSerial(), "ibeacon", setting.getUuid()
                    , setting.getMajor(), setting.getMinor(), coordinates, Boolean.parseBoolean(appConfigProperties.getBeaconsDefaultEnabledStatus()), jsonObject.toString());
            campHillResponses.add(merakiResponse);
        }
    }

    private List<String> fetchOrganizationIds(HttpEntity<Object> entity) {
        logger.info("Inside fetch organizationIds");
        final ResponseEntity<Object> organizationData = restTemplate.exchange(appConfigProperties.getOrganizationsEndpoint(), HttpMethod.GET,
                entity, Object.class);
        OrganizationData[] organizationDataArray = objectMapper.convertValue(organizationData.getBody(), OrganizationData[].class);
        if (organizationDataArray == null || organizationDataArray.length == 0) {
            throw new IllegalStateException("No organizations returned from Meraki organizations endpoint");
        }
        List<String> organizationIds = Arrays.stream(organizationDataArray)
                .map(OrganizationData::getId)
                .filter(StringUtils::isNotBlank)
                .map(organizationId -> {
                    validateOrganizationId(organizationId);
                    return organizationId;
                })
                .collect(Collectors.toList());
        if (organizationIds.isEmpty()) {
            throw new IllegalStateException("No valid organization ids returned from Meraki organizations endpoint");
        }
        return organizationIds;
    }

    private void validateOrganizationId(String organizationId) {
        if (organizationId == null || organizationId.isEmpty()) {
            throw new IllegalArgumentException("organizationId must not be null or empty");
        }
        if (organizationId.length() > 128) {
            throw new IllegalArgumentException("organizationId is too long");
        }
        if (!organizationId.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("organizationId contains invalid characters");
        }
    }

    private void validateNetworkId(String networkId) {
        if (networkId == null || networkId.isEmpty()) {
            throw new IllegalArgumentException("networkId must not be null or empty");
        }
        if (networkId.length() > 128) {
            throw new IllegalArgumentException("networkId is too long");
        }
        if (!networkId.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("networkId contains invalid characters");
        }
    }

    private void validateSerialNumber(String serialNumber) {
        if (serialNumber == null || serialNumber.isEmpty()) {
            throw new IllegalArgumentException("serialNumber must not be null or empty");
        }
        if (serialNumber.length() > 128) {
            throw new IllegalArgumentException("serialNumber is too long");
        }
        if (!serialNumber.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("serialNumber contains invalid characters");
        }
    }

    @Override
    public Map<String, List<MerakiResponse>> splitAndSendEntriesByDivisions(List<MerakiResponse> merakiResponseList) {
        logger.info("BeaconDataToRadarSystemService | splitAndSendEntriesByDivisions | Inside");
        Map<String, List<MerakiResponse>> map = new HashMap<>();
        List<MerakiResponse> footlockerEntries = new ArrayList<>();
        List<MerakiResponse> champsSportsEntries = new ArrayList<>();
        List<MerakiResponse> kidsFootlockerEntries = new ArrayList<>();

        // Always publish keys, even if all inputs are invalid/filtered out.
        map.put(BeaconRadarConstants.FOOT_LOCKER, footlockerEntries);
        map.put(BeaconRadarConstants.KIDS_FOOTLOCKER, kidsFootlockerEntries);
        map.put(BeaconRadarConstants.CHAMPS_SPORTS, champsSportsEntries);

        List<Integer> footlockerDivs = footlockerDivisions != null ? footlockerDivisions : DEFAULT_FOOTLOCKER_DIVISIONS;
        List<Integer> kidsDivs = kidsFootlockerDivisions != null ? kidsFootlockerDivisions : DEFAULT_KIDS_FOOTLOCKER_DIVISIONS;
        List<Integer> champsDivs = champsSportsDivisions != null ? champsSportsDivisions : DEFAULT_CHAMPS_SPORTS_DIVISIONS;

        for (MerakiResponse entry : merakiResponseList) {
            String tag = entry != null ? entry.getTag() : null;
            if (StringUtils.isBlank(tag) || tag.length() < 2) {
                continue;
            }
            String divStr = tag.substring(0, 2);
            if (!DIVISION_PATTERN.matcher(divStr).matches()) {
                continue;
            }
            int division = Integer.parseInt(divStr);

            if (footlockerDivs.contains(division)) {
                footlockerEntries.add(entry);
            } else if (kidsDivs.contains(division)) {
                kidsFootlockerEntries.add(entry);
            } else if (champsDivs.contains(division)) {
                champsSportsEntries.add(entry);
            }
        }
        logger.info("footlockerEntries size" + footlockerEntries.size());
        logger.info("champsSportsEntries size" + champsSportsEntries.size());
        logger.info("kidsFootlockerEntries size" + kidsFootlockerEntries.size());
        return map;
    }

    @Override
    public void mapBeaconDataAndSendToRadarSystem(Map<String, List<MerakiResponse>> stringListMap) {
        // Share Radar inventory only when divisions share the same apiKey; keep Champs separate.
        Map<String, RadarResponseData> sharedRadarCacheByApiKey = new HashMap<>();
        // Avoid issuing duplicate deletes for the same Radar id within a single processing run.
        Set<String> deletedRadarIds = new HashSet<>();

        // Aggregate payload per apiKey so invalid-entry deletion is based on the full desired set.
        Map<String, List<MerakiResponse>> payloadByApiKey = new HashMap<>();

        // First pass: build per-apiKey payloads (merge when Footlocker/Kids share key)
        stringListMap.forEach((key, value) -> {
            if (CollectionUtils.isEmpty(value)) {
                return;
            }
            String apiKey = null;
            if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.FOOT_LOCKER)) {
                apiKey = appConfigProperties.getFootlockerApiKey();
            } else if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.KIDS_FOOTLOCKER)) {
                apiKey = appConfigProperties.getKidsFootlockerApiKey();
            } else if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.CHAMPS_SPORTS)) {
                apiKey = appConfigProperties.getChampsSportsApiKey();
            }
            if (StringUtils.isBlank(apiKey)) {
                return;
            }
            payloadByApiKey.computeIfAbsent(apiKey, k -> new ArrayList<>()).addAll(value);
        });

        // Second pass: for each apiKey, fetch inventory once and (optionally) delete invalid entries once.
        Map<String, RadarResponseData> radarByApiKey = new HashMap<>();
        payloadByApiKey.forEach((apiKey, mergedPayload) -> {
            RadarResponseData radar = sharedRadarCacheByApiKey.computeIfAbsent(apiKey, this::getRadarSystemData);
            radarByApiKey.put(apiKey, radar);
            if (Boolean.TRUE.equals(appConfigProperties.getEnableRadarInvalidEntryDeletion())) {
                removeInvalidEntriesAtRadar(radar, mergedPayload, apiKey, deletedRadarIds);
            }
        });

        stringListMap.forEach((key, value) -> {
            if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.FOOT_LOCKER)) {
                String apiKey = appConfigProperties.getFootlockerApiKey();
                filterAndSendBeaconDataToRadar(apiKey, value, radarByApiKey.get(apiKey), deletedRadarIds);
            } else if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.KIDS_FOOTLOCKER)) {
                String apiKey = appConfigProperties.getKidsFootlockerApiKey();
                filterAndSendBeaconDataToRadar(apiKey, value, radarByApiKey.get(apiKey), deletedRadarIds);
            } else if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.CHAMPS_SPORTS)) {
                String apiKey = appConfigProperties.getChampsSportsApiKey();
                filterAndSendBeaconDataToRadar(apiKey, value, radarByApiKey.get(apiKey), deletedRadarIds);
            }
        });
        logger.info("BeaconDataToRadarSystemService | mapBeaconDataAndSendToRadarSystem | Processed all entries to Radar system");
    }


    private void filterAndSendBeaconDataToRadar(String apiKey,
                                               List<MerakiResponse> value,
                                               RadarResponseData radarResponseData,
                                               Set<String> deletedRadarIds) {
        if (CollectionUtils.isEmpty(value) || StringUtils.isBlank(apiKey)) {
            return;
        }

        // Note: invalid-entry deletion is performed once per apiKey in mapBeaconDataAndSendToRadarSystem.

        List<RadarResponseEntryData> radarBeacons = (radarResponseData == null || radarResponseData.getBeacons() == null)
                ? Collections.emptyList()
                : radarResponseData.getBeacons();

        value.stream()
                .filter(Objects::nonNull)
                .filter(entry -> StringUtils.isNotBlank(entry.getTag()) && StringUtils.isNotBlank(entry.getExternalId()))
                .forEach(beaconEntry -> {
                    boolean existsInRadar = radarBeacons.stream()
                            .filter(Objects::nonNull)
                            .filter(radarEntry -> StringUtils.isNotBlank(radarEntry.getTag()) && StringUtils.isNotBlank(radarEntry.getExternalId()))
                            .anyMatch(radarEntry -> StringUtils.equals(beaconEntry.getTag(), radarEntry.getTag())
                                    && StringUtils.equals(beaconEntry.getExternalId(), radarEntry.getExternalId()));

                    if (existsInRadar) {
                        logger.info("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Beacon already present in radar (will update) : {} {}",
                                beaconEntry.getTag(), beaconEntry.getExternalId());
                    }

                    sendDataToRadarSystem(beaconEntry, apiKey);
                });
    }

    public void filterAndSendBeaconDataToRadar(String apiKey, List<MerakiResponse> value) {
        // Backwards-compatible entry point: no cross-division caching/de-dupe.
        RadarResponseData radar = getRadarSystemData(apiKey);
        if (Boolean.TRUE.equals(appConfigProperties.getEnableRadarInvalidEntryDeletion())) {
            removeInvalidEntriesAtRadar(radar, value, apiKey, new HashSet<>());
        }
        filterAndSendBeaconDataToRadar(apiKey, value, radar, new HashSet<>());
    }

    private void removeInvalidEntriesAtRadar(RadarResponseData radarResponseData,
                                             List<MerakiResponse> beaconResponseData,
                                             String apiKey,
                                             Set<String> deletedRadarIds) {
        if (radarResponseData == null
                || CollectionUtils.isEmpty(radarResponseData.getBeacons())
                || CollectionUtils.isEmpty(beaconResponseData)
                || StringUtils.isBlank(apiKey)) {
            return;
        }

        Set<String> beaconTagExternalIds = beaconResponseData.stream()
                .filter(Objects::nonNull)
                .filter(b -> StringUtils.isNotBlank(b.getTag()) && StringUtils.isNotBlank(b.getExternalId()))
                .map(b -> b.getTag() + ":" + b.getExternalId())
                .collect(Collectors.toSet());

        // When multiple divisions share the same Radar workspace (shared apiKey), avoid deleting entries for a
        // different division when they share the same externalId (serial).
        Set<String> payloadExternalIds = beaconResponseData.stream()
                .filter(Objects::nonNull)
                .map(MerakiResponse::getExternalId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        radarResponseData.getBeacons().stream()
                .filter(Objects::nonNull)
                .filter(entry -> StringUtils.isNotBlank(entry.getTag()) && StringUtils.isNotBlank(entry.getExternalId()))
                .filter(entry -> entry.getMetadata() == null || !Boolean.TRUE.equals(entry.getMetadata().isMockLocation()))
                // Only delete beacons that are not present in current payload.
                .filter(entry -> !beaconTagExternalIds.contains(entry.getTag() + ":" + entry.getExternalId()))
                // ...and do not delete beacons that share an externalId with the desired payload.
                .filter(entry -> !payloadExternalIds.contains(entry.getExternalId()))
                .forEach(entry -> {
                    String id = entry.get_id();
                    if (StringUtils.isBlank(id)) {
                        return;
                    }
                    if (deletedRadarIds != null && !deletedRadarIds.add(id)) {
                        // already deleted in this run
                        return;
                    }
                    removeEntryAtRadar(id, apiKey);
                });
    }

    public RadarResponseData getRadarSystemData(String apiKey) {
        RadarResponseData responseData = new RadarResponseData();
        responseData.setBeacons(Collections.emptyList());

        try {
            List<RadarResponseEntryData> finalResponseEntryList = new ArrayList<>();
            int fetched;
            String url = appConfigProperties.getRadarEndPointBeacons() + "?limit=1000";

            do {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set(BeaconRadarConstants.AUTHORIZATION, apiKey);
                HttpEntity<MerakiResponse> entity = new HttpEntity<>(headers);
                ResponseEntity<RadarResponseData> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        RadarResponseData.class
                );

                RadarResponseData body = response.getBody();
                List<RadarResponseEntryData> responseEntryDataList = body != null ? body.getBeacons() : null;
                if (!CollectionUtils.isEmpty(responseEntryDataList)) {
                    fetched = responseEntryDataList.size();
                    finalResponseEntryList.addAll(responseEntryDataList);
                    RadarResponseEntryData last = responseEntryDataList.get(fetched - 1);
                    String createdAt = last != null ? last.getCreatedAt() : null;
                    if (StringUtils.isBlank(createdAt)) {
                        // Cannot paginate safely without createdAt; stop.
                        fetched = 0;
                    } else {
                        url = appConfigProperties.getRadarEndPointBeacons()
                                + "?limit=1000&createdBefore="
                                + createdAt.replace("T", " ").replace("Z", "");
                    }
                } else {
                    fetched = 0;
                }
            } while (fetched == 1000);

            logger.info("BeaconDataToRadarSystemService | getRadarSystemData | Total records size : " + finalResponseEntryList.size());
            responseData.setBeacons(finalResponseEntryList);
        } catch (Exception e) {
            logger.error("BeaconDataToRadarSystemService | getRadarSystemData | Exception : {}", e.getMessage(), e);
        }

        return responseData;
    }

    public void removeEntryAtRadar(String id, String apiKey) {
        try {
            if (id == null || !RADAR_ID_PATTERN.matcher(id).matches()) {
                logger.warn("BeaconDataToRadarSystemService | removeEntryAtRadar | Skipping delete due to invalid id: {}", id);
                return;
            }
            String url = appConfigProperties.getRadarEndPointBeacons() + id;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(BeaconRadarConstants.AUTHORIZATION, apiKey);
            HttpEntity<MerakiResponse> entity = new HttpEntity<>(headers);
            ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, Object.class);
            logger.info("BeaconDataToRadarSystemService | removeEntryAtRadar | Radar Response : {} {}", id, response.getBody());
        } catch (Exception e) {
            logger.error("BeaconDataToRadarSystemService | removeEntryAtRadar | Exception : {}", e.getMessage(), e);
        }
    }


    public void sendDataToRadarSystem(MerakiResponse data, String apiKey) {
        try {
            String url = appConfigProperties.getRadarEndPointBeacons() + data.getTag() + "/" + data.getExternalId();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(BeaconRadarConstants.AUTHORIZATION, apiKey);
            HttpEntity<MerakiResponse> entity = new HttpEntity<>(data, headers);
            ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Object.class);
            logger.info("BeaconDataToRadarSystemService | sendDataToRadarSystem | Radar Response for external Id : " + data.getExternalId() + " Response : " + response.getBody());
        } catch (Exception e) {
            logger.error("BeaconDataToRadarSystemService | sendDataToRadarSystem | Exception : " + e.getMessage());
        }
    }
}
