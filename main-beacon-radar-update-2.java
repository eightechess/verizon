package com.footlocker.store.ble.beacons.service.impl;

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
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.regex.Pattern;


@Slf4j
@Service
@Component
public class BeaconDataToRadarSystemServiceImpl implements BeaconDataToRadarSystemService {

    private static final Pattern RADAR_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("^[^-]+-[A-Za-z]{3}(\\d{7})-[^-]+$");
    private static final Pattern DEVICE_NAME_COMPACT_PATTERN = Pattern.compile("(?i)^[a-z]{3}(\\d{7})(?:[-_\\s].*)?$");
    private static final Pattern DEVICE_NAME_EMBEDDED_PATTERN = Pattern.compile("(?i).*?[a-z]{3}(\\d{7}).*");
    private static final Pattern STORE_ID_PATTERN = Pattern.compile("^\\d{7}$");
    private static final Set<String> NA_DIVISIONS = Set.of("03", "16", "18", "76", "77");
    private static final Set<String> APAC_DIVISIONS = Set.of("24", "28");
    private static final Set<String> EMEA_DIVISIONS = Set.of("31");
    private static final Set<String> FOOT_LOCKER_DIVISIONS = Set.of("03", "24", "28", "31", "76");
    private static final Set<String> KIDS_FOOTLOCKER_DIVISIONS = Set.of("16");
    private static final Set<String> CHAMPS_SPORTS_DIVISIONS = Set.of("18", "77");
    private static final Map<String, Set<String>> REGION_PREFIXES = Map.of(
            BeaconRadarConstants.NA_REGION, NA_DIVISIONS,
            BeaconRadarConstants.APAC_REGION, APAC_DIVISIONS,
            BeaconRadarConstants.EMEA_REGION, EMEA_DIVISIONS
    );
    private static final double MERAKI_REQUESTS_PER_SECOND = 3.0;
    private static final int SETTINGS_MAX_ATTEMPTS = 6;
    private static final long SETTINGS_INITIAL_DELAY_MILLIS = 2_000L;
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000L;
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
        List<String> organizationIds = fetchOrganizationIds(entity);
        Map<String, String> geofenceData = loadGeofenceData();
        ExecutorService executorService = Executors.newFixedThreadPool(6);
        RateLimiter rateLimiter = RateLimiter.create(MERAKI_REQUESTS_PER_SECOND);

        List<MerakiResponse> result = new ArrayList<>();
        try {
            for (String organizationId : organizationIds) {
                Map<String, List<String>> networksList = new HashMap<>();
                List<MerakiResponse> campHillResponses = fetchNetworksList(entity, organizationId, networksList, rateLimiter);
                List<MerakiResponse> organizationResponses = getAllDeviceSettingsAsync(networksList, entity, organizationId,
                        geofenceData, executorService, rateLimiter);
                organizationResponses.addAll(campHillResponses);
                result.addAll(organizationResponses);
            }
        } finally {
            executorService.shutdown();
        }

        // Helps validate whether EMEA/APAC entries exist before tag/externalId filtering.
        logger.info("BeaconDataToRadarSystemService | fetchBeaconData | preDedupPrefixCounts {}",
                summarizeMerakiResponsesByDivisionTag(result));

        // Avoid duplicate upserts when the same beacon appears in more than one org.
        long missingTagOrExternalIdCount = result.stream()
                .filter(entry -> entry == null || StringUtils.isBlank(entry.getTag()) || StringUtils.isBlank(entry.getExternalId()))
                .count();
        if (missingTagOrExternalIdCount > 0) {
            logger.warn("BeaconDataToRadarSystemService | fetchBeaconData | Dropping {} entries with missing tag/externalId before deduplication",
                    missingTagOrExternalIdCount);
        }

        List<MerakiResponse> uniqueResponses = new ArrayList<>(result.stream()
                .filter(entry -> entry != null && StringUtils.isNotBlank(entry.getTag()) && StringUtils.isNotBlank(entry.getExternalId()))
                .collect(Collectors.toMap(
                        r -> r.getTag() + ":" + r.getExternalId(),
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ))
                .values());
        logger.info("BeaconDataToRadarSystemService | fetchBeaconData | postDedupPrefixCounts {}",
                summarizeMerakiResponsesByDivisionTag(uniqueResponses));
        logger.info("final beacons size" + uniqueResponses.size());
        return uniqueResponses;
    }

    private List<MerakiResponse> getAllDeviceSettingsAsync(Map<String, List<String>> networksList
            , HttpEntity<Object> entity, String organizationId, Map<String, String> geofenceData,
            ExecutorService executorService, RateLimiter rateLimiter) throws ExecutionException, InterruptedException, TimeoutException {
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
                        final AtomicInteger unresolvedStoreIdSkips = new AtomicInteger(0);
                        final Map<String, AtomicInteger> settingsFailureByDivision = new ConcurrentHashMap<>();
                        final Map<String, AtomicInteger> nullResponseByDivision = new ConcurrentHashMap<>();
                        Map<String, List<DevicesData>> devicesByDivision = groupDevicesByDivision(devices);
                        String divisionKeysOnly = devicesByDivision.keySet().stream()
                                .sorted()
                                .collect(Collectors.joining(",", "{", "}"));
                        logger.info("BeaconDataToRadarSystemService | getAllDeviceSettingsAsync | network:{} devicesByDivision:{}",
                                networkId, divisionKeysOnly);
                        List<CompletableFuture<List<MerakiResponse>>> deviceFutures = devices.stream()
                                .map(device ->
                                        withRetry(() -> {
                                            rateLimiter.acquire();
                                            logger.info("calling settings api for serial :" + device.getSerial());
                                            return fetchSettings(device.getSerial(), entity);
                                        }, SETTINGS_MAX_ATTEMPTS, SETTINGS_INITIAL_DELAY_MILLIS, executorService)
                                                .orTimeout(45, TimeUnit.MINUTES)
                                                .handle((settings, ex) -> {
                                                    if (ex != null) {
                                                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                                        String division = deriveDivisionFromStoreId(device.getStoreId());
                                                        incrementDivisionCounter(settingsFailureByDivision, division);
                                                        logger.error("BeaconDataToRadarSystemService | getAllDeviceSettingsAsync | settings failed for serial:{} network:{} storeId:{} division:{} : [{}] {}",
                                                                device.getSerial(),
                                                                networkId,
                                                                StringUtils.defaultIfBlank(device.getStoreId(), "<blank>"),
                                                                division,
                                                                cause.getClass().getSimpleName(),
                                                                cause.getMessage());
                                                        return null;
                                                    }
                                                    return settings;
                                                })
                                                .thenApply((Function<SettingsData, List<MerakiResponse>>) settings -> {
                                                    if (settings == null) {
                                                        return Collections.emptyList();
                                                    }
                                                    if (StringUtils.isNotBlank(device.getStoreId())) {
                                                        MerakiResponse response = populateMerakiResponses(device, settings, device.getStoreId(), geofenceData);
                                                        if (response == null) {
                                                            incrementDivisionCounter(nullResponseByDivision, deriveDivisionFromStoreId(device.getStoreId()));
                                                        }
                                                        return response == null ? Collections.emptyList() : List.of(response);
                                                    }
                                                    if (CollectionUtils.isEmpty(storeIds)) {
                                                        unresolvedStoreIdSkips.incrementAndGet();
                                                        logger.debug("BeaconDataToRadarSystemService | getAllDeviceSettingsAsync | Skipping serial {} because neither device-name storeId nor network-name storeId resolved for network {}",
                                                                device.getSerial(), networkId);
                                                        return Collections.emptyList();
                                                    }
                                                    return storeIds.stream()
                                                            .map(storeId -> {
                                                                MerakiResponse response = populateMerakiResponses(device, settings, storeId, geofenceData);
                                                                if (response == null) {
                                                                    incrementDivisionCounter(nullResponseByDivision, deriveDivisionFromStoreId(storeId));
                                                                }
                                                                return response;
                                                            })
                                                            .filter(Objects::nonNull)
                                                            .collect(Collectors.toList());
                                                })
                                ).collect(Collectors.toList());
                        return CompletableFuture.allOf(deviceFutures.toArray(new CompletableFuture[0]))
                                .thenApply(v -> {
                                    int skippedDeviceCount = unresolvedStoreIdSkips.get();
                                    if (skippedDeviceCount > 0) {
                                        logger.warn("BeaconDataToRadarSystemService | getAllDeviceSettingsAsync | Skipped {} devices for network {} because neither device-name storeId nor network-name storeId resolved",
                                                skippedDeviceCount, networkId);
                                    }
                                    List<MerakiResponse> responses = deviceFutures.stream()
                                            .map(CompletableFuture::join)
                                            .flatMap(List::stream)
                                            .collect(Collectors.toList());
                                    logger.info("BeaconDataToRadarSystemService | getAllDeviceSettingsAsync | network:{} summary | inputByDivision:{} | settingsFailuresByDivision:{} | ResponsesByDivision:{} | outputByDivision:{}",
                                            networkId,
                                            summarizeDeviceCountsByDivision(devicesByDivision),
                                            stringifyDivisionCounts(settingsFailureByDivision),
                                            stringifyDivisionCounts(nullResponseByDivision),
                                            summarizeMerakiResponsesByDivisionTag(responses));
                                    return responses;
                                });
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

    private Map<String, String> loadGeofenceData() throws IOException {
        List<GeofenceData> geofenceDataList = objectMapper.readValue(
                azureBlobStorageService.readFileFromBlob(BeaconRadarConstants.AZURE_BLOB_FILE_NAME),
                new TypeReference<List<GeofenceData>>() {
                });
        return geofenceDataList.stream()
                .collect(Collectors.toMap(GeofenceData::getStoreId, GeofenceData::getStoreName));

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
        List<DevicesData> fetchedDevices = List.of(objectMapper.convertValue(devices.getBody(), DevicesData[].class));
        return fetchedDevices.stream()
                .map(this::enrichDeviceWithStoreIdAndDivision)
                .collect(Collectors.toList());
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

    private <T> CompletableFuture<T> withRetry(Supplier<T> supplier, int maxAttempts, long initalDelayMillis, ExecutorService executorService) {
        return executeWithRetryAsync(supplier, maxAttempts, initalDelayMillis, 0, executorService);
    }

    private <T> CompletableFuture<T> executeWithRetryAsync(Supplier<T> supplier, int maxAttempts, long delay, int attempt, ExecutorService executorService) {
        return CompletableFuture.supplyAsync(supplier, executorService)
                .handle((res, ex) -> {
                    if (ex == null) return CompletableFuture.completedFuture(res);
                    Throwable rootCause = unwrapThrowable(ex);
                    if (!isRetryableException(rootCause)) {
                        logger.error("BeaconDataToRadarSystemService | withRetry | non-retryable failure {}. Failing fast.", formatRetryError(rootCause));
                        return CompletableFuture.<T>failedFuture(rootCause);
                    }
                    int currentAttempt = attempt + 1;
                    if (currentAttempt >= maxAttempts) {
                        return CompletableFuture.<T>failedFuture(rootCause);
                    }
                    long sleepDelay = resolveRetryDelayMillis(rootCause, delay);
                    logger.warn("BeaconDataToRadarSystemService | withRetry | attempt:{}/{} failed with {}. Retrying after {} ms", currentAttempt, maxAttempts, formatRetryError(rootCause), sleepDelay);
                    long nextDelay = Math.min(delay * 2, MAX_RETRY_DELAY_MILLIS);
                    Executor delayedExec = CompletableFuture.delayedExecutor(sleepDelay, TimeUnit.MILLISECONDS, executorService);
                    return CompletableFuture.supplyAsync(() -> null, delayedExec)
                            .thenCompose(v -> executeWithRetryAsync(supplier, maxAttempts, nextDelay, currentAttempt, executorService));
                }).thenCompose(Function.identity());
    }

    private Throwable unwrapThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof HttpStatusCodeException statusCodeException) {
            int statusCode = statusCodeException.getStatusCode().value();
            return statusCode == 429 || statusCode >= 500;
        }
        return throwable instanceof ResourceAccessException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectException
                || throwable instanceof IOException;
    }

    private String formatRetryError(Throwable throwable) {
        if (throwable instanceof HttpStatusCodeException statusCodeException) {
            String responseBody = StringUtils.defaultIfBlank(statusCodeException.getResponseBodyAsString(), "<empty>");
            return statusCodeException.getClass().getSimpleName()
                    + " status=" + statusCodeException.getStatusCode().value()
                    + " body=" + responseBody;
        }
        return throwable.getClass().getSimpleName() + " message=" + StringUtils.defaultString(throwable.getMessage(), "<empty>");
    }

    private long resolveRetryDelayMillis(Throwable throwable, long defaultDelayMillis) {
        if (throwable instanceof HttpStatusCodeException statusCodeException
                && statusCodeException.getStatusCode().value() == 429) {
            HttpHeaders responseHeaders = statusCodeException.getResponseHeaders();
            String retryAfter = responseHeaders != null ? responseHeaders.getFirst("Retry-After") : null;
            if (StringUtils.isNotBlank(retryAfter) && StringUtils.isNumeric(retryAfter.trim())) {
                long retryAfterMillis = Long.parseLong(retryAfter.trim()) * 1000L;
                return Math.min(Math.max(retryAfterMillis, defaultDelayMillis), MAX_RETRY_DELAY_MILLIS);
            }
            long throttledDelay = Math.max(defaultDelayMillis, SETTINGS_INITIAL_DELAY_MILLIS);
            return Math.min(throttledDelay, MAX_RETRY_DELAY_MILLIS);
        }
        return Math.min(defaultDelayMillis, MAX_RETRY_DELAY_MILLIS);
    }

    private MerakiResponse populateMerakiResponses(DevicesData device, SettingsData setting, String storeId, Map<String, String> geofenceData) {
        if (!isValidStoreId(storeId)) {
            logger.warn("BeaconDataToRadarSystemService | populateMerakiResponses | Invalid storeId '{}' for serial {}", storeId, device != null ? device.getSerial() : null);
            return null;
        }
        String serial = device != null ? StringUtils.trimToNull(device.getSerial()) : null;
        // Description must come from source-of-truth blob mapping only.
        String storeName = geofenceData != null ? StringUtils.trimToNull(geofenceData.get(storeId)) : null;
        String storeNameSource = "blob-only";
        logger.info("BeaconDataToRadarSystemService | populateMerakiResponses | storeId:{} | serial:{} | division:{} | region:{} | storeNameSource:{} | resolvedDescription:{}",
                StringUtils.defaultIfBlank(storeId, "<blank>"),
                StringUtils.defaultIfBlank(serial, "<blank>"),
                deriveDivisionFromStoreId(storeId),
                deriveRegionFromDivision(deriveDivisionFromStoreId(storeId)),
                storeNameSource,
                StringUtils.defaultIfBlank(storeName, "<blank>"));
        String uuid = setting != null ? StringUtils.trimToNull(setting.getUuid()) : null;
        String major = setting != null ? StringUtils.trimToNull(setting.getMajor()) : null;
        String minor = setting != null ? StringUtils.trimToNull(setting.getMinor()) : null;
        String tag = StringUtils.trimToNull(storeId);
        String externalId = serial;
        String latitude = (device != null && device.getLat() != null) ? String.valueOf(device.getLat()) : null;
        String longitude = (device != null && device.getLng() != null) ? String.valueOf(device.getLng()) : null;

        boolean missingStoreName = storeName == null;
        boolean missingUuid = uuid == null;
        boolean missingMajor = major == null;
        boolean missingMinor = minor == null;
        boolean missingTag = tag == null;
        boolean missingExternalId = externalId == null;
        boolean missingLatitude = latitude == null;
        boolean missingLongitude = longitude == null;

        logger.info("BeaconDataToRadarSystemService | populateMerakiResponses | beaconFieldAudit | storeId:{} | serial:{} | tag:{} | externalId:{} | storeName:{} | uuid:{} | major:{} | minor:{} | lat:{} | lng:{} | enabledDefault:{} | missingFlags storeName:{} uuid:{} major:{} minor:{} tag:{} externalId:{} lat:{} lng:{}",
                StringUtils.defaultIfBlank(storeId, "<blank>"),
                StringUtils.defaultIfBlank(serial, "<blank>"),
                StringUtils.defaultIfBlank(tag, "<blank>"),
                StringUtils.defaultIfBlank(externalId, "<blank>"),
                StringUtils.defaultIfBlank(storeName, "<blank>"),
                StringUtils.defaultIfBlank(uuid, "<blank>"),
                StringUtils.defaultIfBlank(major, "<blank>"),
                StringUtils.defaultIfBlank(minor, "<blank>"),
                StringUtils.defaultIfBlank(latitude, "<blank>"),
                StringUtils.defaultIfBlank(longitude, "<blank>"),
                appConfigProperties.getBeaconsDefaultEnabledStatus(),
                missingStoreName,
                missingUuid,
                missingMajor,
                missingMinor,
                missingTag,
                missingExternalId,
                missingLatitude,
                missingLongitude);

        if (missingStoreName || missingUuid || missingMajor || missingMinor) {
            logger.info("BeaconDataToRadarSystemService | populateMerakiResponses | Missing mandatory field(s) for serial:{} storeId:{} | storeName:{} uuid:{} major:{} minor:{} | missingFlags storeName:{} uuid:{} major:{} minor:{} | Hence ignoring it.",
                    StringUtils.defaultIfBlank(serial, "<blank>"),
                    StringUtils.defaultIfBlank(storeId, "<blank>"),
                    StringUtils.defaultIfBlank(storeName, "<blank>"),
                    StringUtils.defaultIfBlank(uuid, "<blank>"),
                    StringUtils.defaultIfBlank(major, "<blank>"),
                    StringUtils.defaultIfBlank(minor, "<blank>"),
                    missingStoreName,
                    missingUuid,
                    missingMajor,
                    missingMinor);
            return null;
        }
        List<String> coordinates = Arrays.asList(longitude, latitude);
        String metadata = buildMetadata(storeId);
        return new MerakiResponse(storeName.replaceAll(appConfigProperties.getDescriptionRegex(),""), storeId, device.getSerial(), "ibeacon", uuid
                , major, minor, coordinates, Boolean.parseBoolean(appConfigProperties.getBeaconsDefaultEnabledStatus()), metadata);

    }

    private List<MerakiResponse> fetchNetworksList(HttpEntity<Object> entity, String organizationId, Map<String, List<String>> networkList, RateLimiter rateLimiter) {
        validateOrganizationId(organizationId);
        List<MerakiResponse> campHillResponses = new ArrayList<>();

        String getNetworksUrl = appConfigProperties.getNetworksEndpointPrefix()
                + organizationId
                + appConfigProperties.getNetworksEndpointSuffix();

        final int maxAttempts = 3;
        ResponseEntity<Object> networks = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                rateLimiter.acquire();
                networks = restTemplate.exchange(getNetworksUrl, HttpMethod.GET, entity, Object.class);
                break;
            } catch (HttpStatusCodeException ex) {
                if (!HttpStatus.TOO_MANY_REQUESTS.equals(ex.getStatusCode()) || attempt == maxAttempts) {
                    throw ex;
                }
                logger.warn("BeaconDataToRadarSystemService | fetchNetworksList | Received 429 from Meraki networks API for organization {} on attempt {} of {}. Retrying after backoff.",
                        organizationId, attempt, maxAttempts);
                try {
                    TimeUnit.SECONDS.sleep(attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying Meraki networks API call", interruptedException);
                }
            }
        }

        NetworkData[] networkData = objectMapper.convertValue(networks.getBody(), NetworkData[].class);
        logger.info("network count" + networkData.length);
        Arrays.stream(networkData).forEach(s -> {
            String networkId = s.getId();
            String networkName = StringUtils.trimToEmpty(s.getName());
            String primaryStoreId = null;
            String secondaryStoreId = null;
            if (StringUtils.startsWithIgnoreCase(networkName, "footl-store")) {
                primaryStoreId = StringUtils.right(networkName, 7);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "footl-combo")) {
                primaryStoreId = StringUtils.right(networkName, 7);
                secondaryStoreId = StringUtils.substring(networkName, 12, 19);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "combo-store")) {
                primaryStoreId = StringUtils.right(networkName, 7);
                secondaryStoreId = StringUtils.substring(networkName, 12, 19);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "combo-")) {
                primaryStoreId = StringUtils.right(networkName, 7);
                secondaryStoreId = StringUtils.substring(networkName, 6, 13);
            } else if (StringUtils.startsWithIgnoreCase(networkName, "store-")) {
                primaryStoreId = StringUtils.right(networkName, 7);
                if (networkName.length() > 14) {
                    secondaryStoreId = StringUtils.substring(networkName, 6, 13);
                }
            } else if (networkName.equals(appConfigProperties.getCampHillLabName()) && Boolean.TRUE.equals(Boolean.parseBoolean(appConfigProperties.getIsCampHillBeaconNeeded()))) {
                sendDataForCampHillBeacons(entity,organizationId,networkId,campHillResponses, rateLimiter);
            }
            if (isValidStoreId(primaryStoreId) && isValidStoreId(secondaryStoreId)) {
                networkList.put(networkId, List.of(primaryStoreId, secondaryStoreId));
            } else if (isValidStoreId(primaryStoreId)) {
                networkList.put(networkId, List.of(primaryStoreId));
            } else if (!networkName.equals(appConfigProperties.getCampHillLabName())) {
                // Keep processing this network; device-level naming may still provide storeId.
                networkList.put(networkId, Collections.emptyList());
                logger.debug("BeaconDataToRadarSystemService | fetchNetworksList | No legacy store parsed from network name '{}'; deferring to device-name storeId parsing for network {}",
                        networkName, networkId);
            }
        });
        logger.info("storeId mapping count {}", networkList.size());
        return campHillResponses;
    }

    private void sendDataForCampHillBeacons(HttpEntity<Object> entity, String organizationId, String networkId, List<MerakiResponse> campHillResponses, RateLimiter rateLimiter) {
        rateLimiter.acquire();
        List<DevicesData> devicesData = fetchDevices(networkId,entity,organizationId);
        if (devicesData != null) {
            devicesData = devicesData.stream()
                    .filter(device -> {
                        if (device == null) {
                            logger.warn("BeaconDataToRadarSystemService | sendDataForCampHillBeacons | Null device found for network {}, skipping.", networkId);
                            return false;
                        }
                        if (device.getSerial() == null) {
                            logger.warn("BeaconDataToRadarSystemService | sendDataForCampHillBeacons | Device missing serial for network {}, skipping.", networkId);
                            return false;
                        }
                        return true;
                    })
                    .collect(java.util.stream.Collectors.toList());
        }
        List<String> campHillStoreIds = parseCampHillStoreIds();
        int deviceCount = devicesData == null ? 0 : devicesData.size();
        if (campHillStoreIds.isEmpty()) {
            logger.warn("BeaconDataToRadarSystemService | sendDataForCampHillBeacons | No configured CampHill storeIds. Skipping {} devices for network {}",
                    deviceCount, networkId);
            return;
        }

        int pairedCount = Math.min(deviceCount, campHillStoreIds.size());
        if (campHillStoreIds.size() < deviceCount) {
            logger.warn("BeaconDataToRadarSystemService | sendDataForCampHillBeacons | Configured CampHill storeIds ({}) are fewer than devices ({}). Processing first {} entries.",
                    campHillStoreIds.size(), deviceCount, pairedCount);
        }

        for (int i = 0; i < pairedCount; i++) {
            DevicesData device = devicesData.get(i);
            String normalizedStoreId = campHillStoreIds.get(i);
            String latitude = device.getLat() != null ? String.valueOf(device.getLat()) : null;
            String longitude = device.getLng() != null ? String.valueOf(device.getLng()) : null;
            List<String> coordinates = Arrays.asList(longitude, latitude);
            String metadata = buildMetadata(normalizedStoreId);
            rateLimiter.acquire();
            SettingsData setting = fetchSettings(device.getSerial(),entity);
            MerakiResponse merakiResponse = new MerakiResponse(appConfigProperties.getCampHillLabName().replaceAll(appConfigProperties.getDescriptionRegex(),""), normalizedStoreId, device.getSerial(), "ibeacon", setting.getUuid()
                    , setting.getMajor(), setting.getMinor(), coordinates, Boolean.parseBoolean(appConfigProperties.getBeaconsDefaultEnabledStatus()), metadata);
            campHillResponses.add(merakiResponse);
        }
    }

    private List<String> parseCampHillStoreIds() {
        String configuredStoreNumbers = appConfigProperties.getCampHillStoreNumbers();
        if (StringUtils.isBlank(configuredStoreNumbers)) {
            return Collections.emptyList();
        }
        return Arrays.stream(configuredStoreNumbers.split(","))
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
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
        Map<String, Long> regionalBeaconCounts = aggregateBeaconCountsByRegion(merakiResponseList);

        for (MerakiResponse entry : merakiResponseList) {
            String divisionPrefix = getTagPrefix(entry);
            if (divisionPrefix == null) {
                continue;
            }
            if (FOOT_LOCKER_DIVISIONS.contains(divisionPrefix)) {
                footlockerEntries.add(entry);
            } else if (KIDS_FOOTLOCKER_DIVISIONS.contains(divisionPrefix)) {
                kidsFootlockerEntries.add(entry);
            } else if (CHAMPS_SPORTS_DIVISIONS.contains(divisionPrefix)) {
                champsSportsEntries.add(entry);
            }
        }
        map.put(BeaconRadarConstants.FOOT_LOCKER, footlockerEntries);
        map.put(BeaconRadarConstants.KIDS_FOOTLOCKER, kidsFootlockerEntries);
        map.put(BeaconRadarConstants.CHAMPS_SPORTS, champsSportsEntries);
        logger.info("footlockerEntries size" + footlockerEntries.size());
        logger.info("champsSportsEntries size" + champsSportsEntries.size());
        logger.info("kidsFootlockerEntries size" + kidsFootlockerEntries.size());
        logger.info("regionalBeaconCounts NA:{} APAC:{} EMEA:{}",
                regionalBeaconCounts.get(BeaconRadarConstants.NA_REGION),
                regionalBeaconCounts.get(BeaconRadarConstants.APAC_REGION),
                regionalBeaconCounts.get(BeaconRadarConstants.EMEA_REGION));
        return map;
    }

    private Map<String, Long> aggregateBeaconCountsByRegion(List<MerakiResponse> merakiResponseList) {
        Map<String, Long> regionCounts = new LinkedHashMap<>();
        regionCounts.put(BeaconRadarConstants.NA_REGION, 0L);
        regionCounts.put(BeaconRadarConstants.APAC_REGION, 0L);
        regionCounts.put(BeaconRadarConstants.EMEA_REGION, 0L);

        for (MerakiResponse entry : merakiResponseList) {
            String region = resolveRegionByTag(entry);
            if (region != null) {
                regionCounts.put(region, regionCounts.get(region) + 1);
            }
        }
        return regionCounts;
    }

    private String resolveRegionByTag(MerakiResponse entry) {
        String tagPrefix = getTagPrefix(entry);
        if (tagPrefix == null) {
            return null;
        }
        return REGION_PREFIXES.entrySet().stream()
                .filter(region -> region.getValue().contains(tagPrefix))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private boolean isValidStoreId(String storeId) {
        if (StringUtils.isBlank(storeId)) {
            return false;
        }
        String normalizedStoreId = storeId.trim();
        return STORE_ID_PATTERN.matcher(normalizedStoreId).matches();
    }

    private String getTagPrefix(MerakiResponse entry) {
        if (entry == null || StringUtils.isBlank(entry.getTag())) {
            return null;
        }
        String normalizedTag = entry.getTag().trim();
        if (!isValidStoreId(normalizedTag)) {
            return null;
        }
        return normalizedTag.substring(0, 2);
    }

    private String extractStoreIdFromDeviceName(String deviceName) {
        if (StringUtils.isBlank(deviceName)) {
            return null;
        }
        String normalizedName = deviceName.trim();
        java.util.regex.Matcher matcher = DEVICE_NAME_PATTERN.matcher(normalizedName);
        if (matcher.matches()) {
            String storeId = matcher.group(1);
            return isValidStoreId(storeId) ? storeId : null;
        }

        java.util.regex.Matcher compactMatcher = DEVICE_NAME_COMPACT_PATTERN.matcher(normalizedName);
        if (compactMatcher.matches()) {
            String storeId = compactMatcher.group(1);
            return isValidStoreId(storeId) ? storeId : null;
        }

        // Fallback for names where the 3-letter prefix + 7-digit store appears in the middle.
        java.util.regex.Matcher embeddedMatcher = DEVICE_NAME_EMBEDDED_PATTERN.matcher(normalizedName);
        if (embeddedMatcher.matches()) {
            String storeId = embeddedMatcher.group(1);
            return isValidStoreId(storeId) ? storeId : null;
        }
        return null;
    }

    private String deriveDivisionFromStoreId(String storeId) {
        if (!isValidStoreId(storeId)) {
            return BeaconRadarConstants.UNKNOWN;
        }
        return StringUtils.substring(storeId, 0, 2);
    }

    private String deriveRegionFromDivision(String division) {
        if (StringUtils.isBlank(division) || StringUtils.equals(division, BeaconRadarConstants.UNKNOWN)) {
            return BeaconRadarConstants.UNKNOWN;
        }
        return REGION_PREFIXES.entrySet().stream()
                .filter(entry -> entry.getValue().contains(division))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(BeaconRadarConstants.UNKNOWN);
    }

    private String buildMetadata(String storeId) {
        String normalizedStoreId = StringUtils.trimToNull(storeId);
        String division = deriveDivisionFromStoreId(normalizedStoreId);
        String region = deriveRegionFromDivision(division);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("storeId", normalizedStoreId);
        jsonObject.put("division", division);
        jsonObject.put("region", region);
        return jsonObject.toString();
    }

    private DevicesData enrichDeviceWithStoreIdAndDivision(DevicesData device) {
        if (device == null) {
            return null;
        }
        String storeId = extractStoreIdFromDeviceName(device.getName());
        device.setStoreId(storeId);
        device.setDivision(deriveDivisionFromStoreId(storeId));
        return device;
    }

    private Map<String, List<DevicesData>> groupDevicesByDivision(List<DevicesData> devices) {
        Map<String, List<DevicesData>> groupedDevices = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(devices)) {
            return groupedDevices;
        }
        for (DevicesData device : devices) {
            DevicesData enrichedDevice = enrichDeviceWithStoreIdAndDivision(device);
            if (enrichedDevice == null) {
                continue;
            }
            String divisionKey = StringUtils.defaultIfBlank(enrichedDevice.getDivision(), BeaconRadarConstants.UNKNOWN);
            groupedDevices.computeIfAbsent(divisionKey, key -> new ArrayList<>()).add(enrichedDevice);
        }
        return groupedDevices;
    }

    private void incrementDivisionCounter(Map<String, AtomicInteger> divisionCounter, String division) {
        String divisionKey = StringUtils.defaultIfBlank(division, BeaconRadarConstants.UNKNOWN);
        divisionCounter.computeIfAbsent(divisionKey, key -> new AtomicInteger()).incrementAndGet();
    }

    private String stringifyDivisionCounts(Map<String, AtomicInteger> divisionCounter) {
        if (divisionCounter == null || divisionCounter.isEmpty()) {
            return "{}";
        }
        return divisionCounter.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().get(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ))
                .toString();
    }

    private String summarizeDeviceCountsByDivision(Map<String, List<DevicesData>> devicesByDivision) {
        if (devicesByDivision == null || devicesByDivision.isEmpty()) {
            return "{}";
        }
        return devicesByDivision.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() == null ? 0 : entry.getValue().size(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ))
                .toString();
    }

    private String summarizeMerakiResponsesByDivisionTag(List<MerakiResponse> merakiResponses) {
        if (CollectionUtils.isEmpty(merakiResponses)) {
            return "{}";
        }
        Map<String, Long> countsByDivision = merakiResponses.stream()
                .filter(Objects::nonNull)
                .map(this::getTagPrefix)
                .map(prefix -> StringUtils.defaultIfBlank(prefix, BeaconRadarConstants.UNKNOWN))
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        if (countsByDivision.isEmpty()) {
            return "{}";
        }
        return countsByDivision.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, second) -> first,
                        LinkedHashMap::new
                ))
                .toString();
    }

    @Override
    public void mapBeaconDataAndSendToRadarSystem(Map<String, List<MerakiResponse>> stringListMap) {
        Map<String, List<MerakiResponse>> entriesByApiKey = new LinkedHashMap<>();
        Map<String, Set<String>> divisionsByApiKey = new HashMap<>();

        stringListMap.forEach((division, entries) -> {
            String apiKey = getApiKeyForDivision(division);
            if (StringUtils.isBlank(apiKey) || CollectionUtils.isEmpty(entries)) {
                return;
            }
            entriesByApiKey.computeIfAbsent(apiKey, key -> new ArrayList<>()).addAll(entries);
            divisionsByApiKey.computeIfAbsent(apiKey, key -> new LinkedHashSet<>()).add(division);
        });

        divisionsByApiKey.forEach((apiKey, divisions) -> {
            if (divisions.size() > 1) {
                logger.warn("BeaconDataToRadarSystemService | mapBeaconDataAndSendToRadarSystem | Multiple divisions {} mapped to the same Radar project credentials. Processing in one batch to avoid cross-division deletes.",
                        divisions);
            }
        });

        AtomicInteger totalIncoming = new AtomicInteger(0);
        AtomicInteger totalDeduplicated = new AtomicInteger(0);
        AtomicInteger totalApiKeyBatches = new AtomicInteger(0);
        AtomicInteger totalCreated = new AtomicInteger(0);
        AtomicInteger totalAttemptedExistingUpdates = new AtomicInteger(0);
        AtomicInteger totalInvalidInput = new AtomicInteger(0);

        entriesByApiKey.forEach((apiKey, entries) -> {
            totalApiKeyBatches.incrementAndGet();
            totalIncoming.addAndGet(entries.size());
            totalDeduplicated.addAndGet(entries.size());
            ProcessingSummary summary = filterAndSendBeaconDataToRadarAndCollect(apiKey, entries);
            totalCreated.addAndGet(summary.createdCount());
            totalAttemptedExistingUpdates.addAndGet(summary.attemptedExistingUpdateCount());
            totalInvalidInput.addAndGet(summary.invalidInputCount());
        });
        logger.info("BeaconDataToRadarSystemService | mapBeaconDataAndSendToRadarSystem | Total beacon data processed | batches:{} | incoming:{} | deduplicated:{} | duplicatesRemoved:{} | created:{} | attemptedExistingUpdates:{} | invalidInput:{}",
                totalApiKeyBatches.get(),
                totalIncoming.get(),
                totalDeduplicated.get(),
                totalIncoming.get() - totalDeduplicated.get(),
                totalCreated.get(),
                totalAttemptedExistingUpdates.get(),
                totalInvalidInput.get());
    }

    private String getApiKeyForDivision(String division) {
        if (StringUtils.equalsIgnoreCase(division, BeaconRadarConstants.FOOT_LOCKER)) {
            return appConfigProperties.getFootlockerApiKey();
        }
        if (StringUtils.equalsIgnoreCase(division, BeaconRadarConstants.KIDS_FOOTLOCKER)) {
            return appConfigProperties.getKidsFootlockerApiKey();
        }
        if (StringUtils.equalsIgnoreCase(division, BeaconRadarConstants.CHAMPS_SPORTS)) {
            return appConfigProperties.getChampsSportsApiKey();
        }
        return null;
    }

    public void filterAndSendBeaconDataToRadar(String apiKey, List<MerakiResponse> value) {
        filterAndSendBeaconDataToRadarAndCollect(apiKey, value);
    }

    private ProcessingSummary filterAndSendBeaconDataToRadarAndCollect(String apiKey, List<MerakiResponse> value) {
        List<MerakiResponse> incomingBeacons = value == null ? Collections.emptyList() : value;
        RadarResponseData radarResponseData = getRadarSystemData(apiKey);
        if (radarResponseData == null || radarResponseData.getBeacons() == null) {
            logger.warn("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Radar response was null/empty, continuing with no existing beacons cache");

            radarResponseData = emptyRadarResponseData();
            //Beacon Data
            logger.info("Response Data: "+radarResponseData.getBeacons());
        }
        final List<RadarResponseEntryData> existingRadarBeacons = radarResponseData.getBeacons();
        final long invalidRadarEntryCount = existingRadarBeacons.stream()
                .filter(entry -> entry == null || StringUtils.isBlank(entry.getTag()) || StringUtils.isBlank(entry.getExternalId()))
                .count();
        if (invalidRadarEntryCount > 0) {
            logger.warn("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Ignoring {} malformed entries from Radar cache", invalidRadarEntryCount);
        }
        final Map<String, RadarResponseEntryData> existingRadarByKey = existingRadarBeacons.stream()
                .filter(Objects::nonNull)
                .filter(entry -> StringUtils.isNotBlank(entry.getTag()) && StringUtils.isNotBlank(entry.getExternalId()))
                .collect(Collectors.toMap(
                        entry -> entry.getTag() + ":" + entry.getExternalId(),
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
        final Set<String> existingRadarKeys = existingRadarByKey.keySet();
        if (Boolean.TRUE.equals(appConfigProperties.getEnableRadarInvalidEntryDeletion())) {
            removeInvalidEntriesAtRadar(radarResponseData, incomingBeacons, apiKey);
        } else {
            logger.info("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Deletion of invalid Radar entries is disabled");
        }
        int createdCount = 0;
        int attemptedExistingUpdateCount = 0;
        int invalidInputCount = 0;
        for (MerakiResponse beaconEntry : incomingBeacons) {
            if (beaconEntry == null || StringUtils.isBlank(beaconEntry.getTag()) || StringUtils.isBlank(beaconEntry.getExternalId())) {
                logger.warn("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Skipping malformed incoming beacon entry: {}", beaconEntry);
                invalidInputCount++;
                continue;
            }
            String beaconKey = beaconEntry.getTag() + ":" + beaconEntry.getExternalId();
            if (!existingRadarKeys.contains(beaconKey)) {
                sendDataToRadarSystem(beaconEntry, apiKey, "CREATE", beaconKey);
                createdCount++;
            } else {
                logger.info("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Updating existing Radar entry | key:{} | incoming:{} | radar:{}",
                        beaconKey,
                        beaconEntry,
                        existingRadarByKey.get(beaconKey));
                sendDataToRadarSystem(beaconEntry, apiKey, "UPDATE", beaconKey);
                attemptedExistingUpdateCount++;
            }
        }
        logger.info("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Summary | inputCount:{} | radarCacheCount:{} | createdCount:{} | attemptedExistingUpdateCount:{} | invalidInputCount:{}",
                incomingBeacons.size(),
                existingRadarKeys.size(),
                createdCount,
                attemptedExistingUpdateCount,
                invalidInputCount);
        return new ProcessingSummary(createdCount, attemptedExistingUpdateCount, invalidInputCount);
    }

    private record ProcessingSummary(int createdCount, int attemptedExistingUpdateCount, int invalidInputCount) {
    }

    private void removeInvalidEntriesAtRadar(RadarResponseData radarResponseData, List<MerakiResponse> beaconResponseData, String apiKey) {
        if (CollectionUtils.isEmpty(radarResponseData.getBeacons())) {
            return;
        }
        Set<String> beaconTagExternalIds = (beaconResponseData == null ? Collections.<MerakiResponse>emptyList() : beaconResponseData).stream()
                .filter(Objects::nonNull)
                .filter(b -> StringUtils.isNotBlank(b.getTag()) && StringUtils.isNotBlank(b.getExternalId()))
                .map(b -> b.getTag() + ":" + b.getExternalId())
                .collect(Collectors.toSet());
        radarResponseData.getBeacons().stream()
                .filter(Objects::nonNull)
                .filter(entry -> StringUtils.isNotBlank(entry.getTag()) && StringUtils.isNotBlank(entry.getExternalId()))
                .filter(entry -> entry.getMetadata() == null || !entry.getMetadata().isMockLocation())
                .filter(entry -> !beaconTagExternalIds.contains(entry.getTag() + ":" + entry.getExternalId()))
                .forEach(entry -> removeEntryAtRadar(entry.get_id(), apiKey));
    }

    public RadarResponseData getRadarSystemData(String apiKey) {
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
                RadarResponseData responseBody = response.getBody();
                List<RadarResponseEntryData> responseEntryDataList = responseBody != null ? responseBody.getBeacons() : Collections.emptyList();
                if (!CollectionUtils.isEmpty(responseEntryDataList)) {
                    fetched = responseEntryDataList.size();
                    finalResponseEntryList.addAll(responseEntryDataList);
                    url = appConfigProperties.getRadarEndPointBeacons() + "?limit=1000&createdBefore=" + responseEntryDataList.get(fetched - 1).getCreatedAt().replace("T", "%20").replace("Z", "");
                } else {
                    fetched = 0;
                }
            } while (fetched == 1000);
            RadarResponseData responseData = new RadarResponseData();
            logger.info("BeaconDataToRadarSystemService | getRadarSystemData | Total records size : " + finalResponseEntryList.size());
            responseData.setBeacons(finalResponseEntryList);
            return responseData;
        } catch (Exception e) {
            logger.error("BeaconDataToRadarSystemService | getRadarSystemData | Exception : " + e.getMessage());
            return emptyRadarResponseData();
        }
    }

    private RadarResponseData emptyRadarResponseData() {
        RadarResponseData responseData = new RadarResponseData();
        responseData.setBeacons(Collections.emptyList());
        return responseData;
    }

    public void removeEntryAtRadar(String id, String apiKey) {
        try {
            if (id == null || !RADAR_ID_PATTERN.matcher(id).matches()) {
                logger.warn("BeaconDataToRadarSystemService | removeEntryAtRadar | Skipping delete due to invalid id: {}", id);
                return;
            }
            String url = appConfigProperties.getRadarEndPointBeacons();
            if (!url.endsWith("/")) url += "/";
            url += id;
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
        String beaconKey = data == null
                ? "<null>"
                : StringUtils.defaultIfBlank(data.getTag(), "<blank>") + ":" + StringUtils.defaultIfBlank(data.getExternalId(), "<blank>");
        if (data == null) {
            logger.warn("BeaconDataToRadarSystemService | sendDataToRadarSystem | Skipping PUT due to null data | operation:{} | key:{}",
                    "UPSERT",
                    beaconKey);
            return;
        }
        if (StringUtils.isBlank(data.getTag()) || StringUtils.isBlank(data.getExternalId())) {
            logger.warn("BeaconDataToRadarSystemService | sendDataToRadarSystem | Skipping PUT due to blank tag/externalId | operation:{} | key:{}",
                    "UPSERT",
                    beaconKey);
            return;
        }
        sendDataToRadarSystem(data, apiKey, "UPSERT", beaconKey);
    }

    private void sendDataToRadarSystem(MerakiResponse data, String apiKey, String operation, String beaconKey) {
        try {
            String url = appConfigProperties.getRadarEndPointBeacons();
            if (!url.endsWith("/")) url += "/";
            url += data.getTag() + "/" + data.getExternalId();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(BeaconRadarConstants.AUTHORIZATION, apiKey);
            HttpEntity<MerakiResponse> entity = new HttpEntity<>(data, headers);
            logger.info("BeaconDataToRadarSystemService | sendDataToRadarSystem | Sending PUT | operation:{} | key:{} | url:{}",
                    operation,
                    beaconKey,
                    url);
            logger.debug("BeaconDataToRadarSystemService | sendDataToRadarSystem | PUT payload | operation:{} | key:{} | payload:{}",
                    operation,
                    beaconKey,
                    data);
            ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Object.class);
            logger.info("BeaconDataToRadarSystemService | sendDataToRadarSystem | PUT response | operation:{} | key:{} | status:{} | response:{}",
                    operation,
                    beaconKey,
                    response.getStatusCode(),
                    response.getBody());
        } catch (Exception e) {
            logger.error("BeaconDataToRadarSystemService | sendDataToRadarSystem | PUT failed | operation:{} | key:{} | error:{}",
                    operation,
                    beaconKey,
                    e.getMessage(),
                    e);
        }
    }
}
