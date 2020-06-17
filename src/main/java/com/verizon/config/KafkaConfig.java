package com.verizon.config;

import java.util.HashMap;
import java.util.Map;

import com.verizon.model.Payload;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Log4j2
@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${kafka.host}")
    String kafkaHost;
    @Value("${kafka.port}")
    String kafkaPort;

    @Bean
    public ProducerFactory<String, Payload> producerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHost + ":" + kafkaPort);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Payload> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }


    @Bean
    public ConsumerFactory<String, Payload> payloadConsumerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHost + ":" + kafkaPort);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "group_json");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                new JsonDeserializer<>(Payload.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Payload> payloadKafkaListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Payload> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(payloadConsumerFactory());
        return factory;
    }
}
