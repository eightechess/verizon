package com.verizon.controller;

import com.verizon.model.*;
import com.verizon.repo.PayloadRepo;
import com.verizon.services.PayloadInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Log4j2
@RestController
public class PayloadController {

	@Autowired
	private PayloadInterface payloadInterface;

	@CrossOrigin(origins = "http://localhost:8082")
	@GetMapping ("/getAllParameters")
	public PayloadGroup getPayloads(@RequestParam(required = false) String status){
		return payloadInterface.getPayloads(status);
	}

	@PostMapping("/addrule")
	@Async("threadPoolTaskExecutor")
	public void saveRule(@RequestBody Payload payload) {
		payloadInterface.savePayload(payload);
	}

	@PostMapping("/addgroupname")
	@Async("threadPoolTaskExecutor")
	public void addGroupname(@RequestBody RequestUrlGroupName requestUrlGroupName) {
		payloadInterface.addGroupname(requestUrlGroupName);
	}

	@PostMapping("/friendlyurl")
	@Async("threadPoolTaskExecutor")
	public void addFriendlyUrl(@RequestBody FriendlyUrl friendlyUrl) {
		payloadInterface.addFriendlyUrl(friendlyUrl);
	}

	@CrossOrigin(origins = "http://localhost:8082")
	@GetMapping ("/getgroupnames")
	public List<Group> getGroups(){
		return payloadInterface.getGroups();
	}
	@CrossOrigin(origins = "http://localhost:8082")
	@PostMapping ("/addgroup")
	public void getGroups(@RequestBody Group  group){
		payloadInterface.addGroup(group);
	}

	@PostMapping("/addParameters")
	@Async("threadPoolTaskExecutor")
	public String savePayload(@RequestBody Payload payload) {
		return payloadInterface.savePayload(payload);
	}

	@CrossOrigin(origins = "http://localhost:8082")
	@PostMapping("/parser")
	public String saveParserSettings(@RequestBody ParserSettings parserSettings) {
		return payloadInterface.saveParserSettings(parserSettings);
	}

	@KafkaListener(topics = "REQUEST_SAMPLES",  groupId = "group_json", containerFactory = "payloadKafkaListenerFactory")
	public void payLoadConsumer(Payload payload) {
		payloadInterface.payLoadConsumer(payload);
	}

	@PostMapping("/ignorePayloads")
	@Async("threadPoolTaskExecutor")
	public String ignorePayload(@RequestBody List<Payload> payloads) {
		return payloadInterface.ignorePayload(payloads);
	}

	@GetMapping("/discovered")
	@Async("threadPoolTaskExecutor")
	public Iterable<Payload> Discovered() {
		return payloadInterface.Discovered();
	}

	@GetMapping("/discoveredurls")
	@Async("threadPoolTaskExecutor")
	public List<Payload> DiscoveredUrl() {
		return payloadInterface.DiscoveredUrl();
	}

}
