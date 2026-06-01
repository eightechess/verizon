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
    @Value("${store.divisions.foot_locker}")
    private final List<Integer> footlockerDivisions = Arrays.asList(03, 31, 76);

    @Value("${store.divisions.kids_footlocker}")
    private final List<Integer> kidsFootlockerDivisions = List.of(16);

    @Value("${store.divisions.champs_sports}")
    private final List<Integer> champsSportsDivisions = Arrays.asList(18, 77);
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
        Map<String, List<String>> networksList = new HashMap<>();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(BeaconRadarConstants.MERAKI_API_KEY, appConfigProperties.getMerakiApiKey());
        final HttpEntity<Object> entity = new HttpEntity<>(headers);
        String organizationId = fetchOrganizationId(entity);
        List<MerakiResponse> campHillResponses = fetchNetworksList(entity, organizationId, networksList);
        List<MerakiResponse> result = getAllDeviceSettingsAsync(networksList, entity, organizationId);
        result.addAll(campHillResponses);
        logger.info("final beacons size" + result.size());
        return result;
    }

    private List<MerakiResponse> getAllDeviceSettingsAsync(Map<String, List<String>> networksList
            , HttpEntity<Object> entity, String organizationId) throws JsonProcessingException, ExecutionException, InterruptedException, TimeoutException {
        List<GeofenceData> geofenceDataList = objectMapper.readValue(azureBlobStorageService.readFileFromBlob(BeaconRadarConstants.AZURE_BLOB_FILE_NAME), new TypeReference<List<GeofenceData>>() {
        });
        Map<String, String> geofenceData = geofenceDataList.stream()
                .collect(Collectors.toMap(GeofenceData::getStoreId, GeofenceData::getStoreName));

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
                                                        return storeIds.stream()
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
            long delay = initalDelayMillis;
            while(true) {
                try {
                    return supplier.get();
                } catch (Exception e) {
                    attempt++;
                    if(attempt >= maxAttempts)  throw new CompletionException(e);
                    try{
                        Thread.sleep(delay);
                    }catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(ie);
                    }
                    delay *= 2;
                }
            }
        },executorService);
    }

    private MerakiResponse populateMerakiResponses(DevicesData device, SettingsData setting, String storeName, Map<String, String> geofenceData) {
        List<String> coordinates = Arrays.asList(device.getLng().toString(), device.getLat().toString());
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("storeId", storeName);
        if(geofenceData.get(storeName) == null || setting.getUuid() == null || setting.getMajor() == null || setting.getMinor() == null) {
            log.info("One of the mandatory fields is received as null from meraki api for "+device.getSerial()+" "+storeName +"Hence ignoring it.");
            return null;
        }
        return new MerakiResponse(geofenceData.get(storeName).replaceAll(appConfigProperties.getDescriptionRegex(),""), storeName, device.getSerial(), "ibeacon", setting.getUuid()
                , setting.getMajor(), setting.getMinor(), coordinates, Boolean.parseBoolean(appConfigProperties.getBeaconsDefaultEnabledStatus()), jsonObject.toString());

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
            String storeName = null;
            String secondStoreName = null;
            if (StringUtils.startsWithIgnoreCase(s.getName().trim(), "footl-store")) {
                storeName = StringUtils.right(s.getName().trim(), 7);
            } else if (StringUtils.startsWithIgnoreCase(s.getName().trim(), "footl-combo")) {
                storeName = StringUtils.right(s.getName().trim(), 7);
                secondStoreName = StringUtils.substring(s.getName().trim(), 12, 19);
            } else if (StringUtils.startsWithIgnoreCase(s.getName().trim(), "combo-store")) {
                storeName = StringUtils.right(s.getName().trim(), 7);
                secondStoreName = StringUtils.substring(s.getName().trim(), 12, 19);
            } else if (StringUtils.startsWithIgnoreCase(s.getName().trim(), "combo-")) {
                storeName = StringUtils.right(s.getName().trim(), 7);
                secondStoreName = StringUtils.substring(s.getName().trim(), 6, 13);
            } else if (StringUtils.startsWithIgnoreCase(s.getName().trim(), "store-")) {
                storeName = StringUtils.right(s.getName().trim(), 7);
                if (s.getName().length() > 14) {
                    secondStoreName = StringUtils.substring(s.getName().trim(), 6, 13);
                }
            } else if (s.getName().trim().equals(appConfigProperties.getCampHillLabName()) && Boolean.TRUE.equals(Boolean.parseBoolean(appConfigProperties.getIsCampHillBeaconNeeded()))) {
                sendDataForCampHillBeacons(entity,organizationId,networkId,campHillResponses);
            }
            if (null != storeName && null != secondStoreName && storeName.length() == 7 && secondStoreName.length() ==7) {
                networkList.put(networkId, List.of(storeName, secondStoreName));
            } else if (null != storeName && storeName.length() == 7) {
                networkList.put(networkId, List.of(storeName));
            }
        });
        logger.info("StoreList count" + networkList.values().size());
        return campHillResponses;
    }

    private void sendDataForCampHillBeacons(HttpEntity<Object> entity, String organizationId, String networkId, List<MerakiResponse> campHillResponses) {
        List<DevicesData> devicesData = fetchDevices(networkId,entity,organizationId);
        List<String> stringList = Arrays.asList(appConfigProperties.getCampHillStoreNumbers().split(","));
        for(int i=0 ; i< devicesData.size() ;i++) {
            List<String> coordinates = Arrays.asList(devicesData.get(i).getLng().toString(), devicesData.get(i).getLat().toString());
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("storeId", stringList.get(i));
            SettingsData setting = fetchSettings(devicesData.get(i).getSerial(),entity);
            MerakiResponse merakiResponse = new MerakiResponse(appConfigProperties.getCampHillLabName().replaceAll(appConfigProperties.getDescriptionRegex(),""), stringList.get(i), devicesData.get(i).getSerial(), "ibeacon", setting.getUuid()
                    , setting.getMajor(), setting.getMinor(), coordinates, Boolean.parseBoolean(appConfigProperties.getBeaconsDefaultEnabledStatus()), jsonObject.toString());
            campHillResponses.add(merakiResponse);
        }
    }

    private String fetchOrganizationId(HttpEntity<Object> entity) {
        logger.info("Inside fetch organizationId");
        final ResponseEntity<Object> organizationData = restTemplate.exchange(appConfigProperties.getOrganizationsEndpoint(), HttpMethod.GET,
                entity, Object.class);
        OrganizationData[] organizationDataArray = objectMapper.convertValue(organizationData.getBody(), OrganizationData[].class);
        String organizationId = Arrays.stream(organizationDataArray).iterator().next().getId();
        validateOrganizationId(organizationId);
        return organizationId;
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

        for (MerakiResponse entry : merakiResponseList) {
            if (Objects.nonNull(entry.getTag()) && footlockerDivisions.contains(Integer.valueOf(entry.getTag().substring(0, 2)))) {
                footlockerEntries.add(entry);
            } else if (Objects.nonNull(entry.getTag()) && kidsFootlockerDivisions.contains(Integer.valueOf(entry.getTag().substring(0, 2)))) {
                kidsFootlockerEntries.add(entry);
            } else if (Objects.nonNull(entry.getTag()) && champsSportsDivisions.contains(Integer.valueOf(entry.getTag().substring(0, 2)))) {
                champsSportsEntries.add(entry);
            }
            map.put(BeaconRadarConstants.FOOT_LOCKER, footlockerEntries);
            map.put(BeaconRadarConstants.KIDS_FOOTLOCKER, kidsFootlockerEntries);
            map.put(BeaconRadarConstants.CHAMPS_SPORTS, champsSportsEntries);

        }
        logger.info("footlockerEntries size" + footlockerEntries.size());
        logger.info("champsSportsEntries size" + champsSportsEntries.size());
        logger.info("kidsFootlockerEntries size" + kidsFootlockerEntries.size());
        return map;
    }

    @Override
    public void mapBeaconDataAndSendToRadarSystem(Map<String, List<MerakiResponse>> stringListMap) {
        stringListMap.forEach((key, value) -> {
            if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.FOOT_LOCKER)) {
                String apiKey = appConfigProperties.getFootlockerApiKey();
                filterAndSendBeaconDataToRadar(apiKey, value);
            } else if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.KIDS_FOOTLOCKER)) {
                String apiKey = appConfigProperties.getKidsFootlockerApiKey();
                filterAndSendBeaconDataToRadar(apiKey, value);
            } else if (StringUtils.equalsIgnoreCase(key, BeaconRadarConstants.CHAMPS_SPORTS)) {
                String apiKey = appConfigProperties.getChampsSportsApiKey();
                filterAndSendBeaconDataToRadar(apiKey, value);
            }
        });
        logger.info("BeaconDataToRadarSystemService | mapBeaconDataAndSendToRadarSystem | Processed all entries to Radar system");
    }

    public void filterAndSendBeaconDataToRadar(String apiKey, List<MerakiResponse> value) {
        RadarResponseData radarResponseData = getRadarSystemData(apiKey);
        removeInvalidEntriesAtRadar(radarResponseData,value,apiKey);
        value.forEach(beaconEntry -> {
            if (radarResponseData.getBeacons().stream().noneMatch(radarEntry -> Objects.nonNull(radarEntry) && beaconEntry.getExternalId().equals(radarEntry.getExternalId()))) {
                sendDataToRadarSystem(beaconEntry, apiKey);
            } else {
                logger.info("BeaconDataToRadarSystemService | filterAndSendBeaconDataToRadar | Skipping the entry which is already in radar : " +beaconEntry.getTag() +" "+ beaconEntry.getExternalId());
            }
        });
    }

    private void removeInvalidEntriesAtRadar(RadarResponseData radarResponseData, List<MerakiResponse> beaconResponseData, String apiKey) {
        if (!CollectionUtils.isEmpty(radarResponseData.getBeacons())) {
            Set<String> beaconTagExternalIds = beaconResponseData.stream()
                    .map(b ->b.getTag()+":"+b.getExternalId())
                    .collect(Collectors.toSet());
            radarResponseData.getBeacons().stream()
                    .filter(entry -> entry.getMetadata() == null || !Boolean.TRUE.equals(entry.getMetadata().isMockLocation()))
                    .filter(entry -> !beaconTagExternalIds.contains(entry.getTag()+":"+entry.getExternalId()))
                    .forEach(entry -> removeEntryAtRadar(entry.get_id(), apiKey));
        }
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
                List<RadarResponseEntryData> responseEntryDataList = response.getBody().getBeacons();
                if (!CollectionUtils.isEmpty(responseEntryDataList)) {
                    fetched = responseEntryDataList.size();
                    finalResponseEntryList.addAll(responseEntryDataList);
                    url = appConfigProperties.getRadarEndPointBeacons() + "?limit=1000&createdBefore=" + responseEntryDataList.get(fetched - 1).getCreatedAt().replace("T", " ").replace("Z", "");
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
        }
        return null;
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
