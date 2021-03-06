package com.verizon.controller;

import com.verizon.model.*;
import com.verizon.services.payload.PayloadInterface;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@Log4j2
@RestController
public class PayloadController {

	@Autowired
	private PayloadInterface payloadInterface;

	@CrossOrigin
	@GetMapping ("/getAllParameters")
	public PayloadGroup getPayloads(@RequestParam(required = false) String status){
		return payloadInterface.getPayloads(status);
	}

	@PostMapping(path = "/addParameters")
	public String savePayload(@RequestBody Payload payload) {
		payload.setCreatedDate( new Date());
		return payloadInterface.savePayload(payload);
	}

	@CrossOrigin
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

	@CrossOrigin
	@GetMapping("/discovered")
	public Iterable<Payload> Discovered() {
		return payloadInterface.Discovered();
	}

	@CrossOrigin
	@GetMapping("/discoveredurls")
	public List<Payload> DiscoveredUrl() {
		return payloadInterface.DiscoveredUrl();
	}


}
