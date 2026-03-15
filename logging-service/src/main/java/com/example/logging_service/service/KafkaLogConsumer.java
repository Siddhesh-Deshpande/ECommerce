//package com.example.logging_service.service;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.node.ArrayNode;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import org.apache.kafka.clients.consumer.ConsumerRecord;
//import org.springframework.kafka.annotation.KafkaListener;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Service;
//
//import java.io.File;
//import java.io.IOException;
//import java.util.*;
//import java.util.stream.Collectors;
//import java.util.stream.StreamSupport;
//
//@Service
//class KafkaLogConsumer {
//    private final ObjectMapper mapper = new ObjectMapper();
//    private final File globalFile = new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/events.json");
//
//    // Map topics to their own file
//    private final Map<String, File> topicFiles = new HashMap<>() {{
//        put("coor-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/coor-service.json"));
//        put("inventory-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/inventory-service.json"));
//        put("payment-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/payment-service.json"));
//        put("order-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/order-service.json"));
//    }};
//
//    @KafkaListener(topics = {"coor-service","inventory-service","payment-service","order-service"}, groupId = "log-consumer-group")
//    public void consume(ConsumerRecord<String, String> record) {
//        try {
//            JsonNode eventNode = mapper.readTree(record.value());
//
//            // Write to global file
//            writeToFile(globalFile, eventNode);
//
//            // Write to topic-specific file
//            File topicFile = topicFiles.get(record.topic());
//            if (topicFile != null) {
//                writeToFile(topicFile, eventNode);
//            }
//
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void writeToFile(File file, JsonNode eventNode) throws IOException {
//        ObjectNode root;
//
//        if (file.exists() && file.length() > 0) {
//            root = (ObjectNode) mapper.readTree(file);
//        } else {
//            root = mapper.createObjectNode();
//            root.putArray("logs");
//        }
//
//        ArrayNode logsArray = (ArrayNode) root.get("logs");
//        logsArray.add(eventNode);
//
//        mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
//    }
//
//    // ✅ Scheduled independent sorting task (runs every 60 seconds)
//    @Scheduled(fixedDelay = 1000)
//    public void scheduledSortTask() {
//        try {
//            // Sort global file
//            sortFileByTimestamp(globalFile);
//
//            // Sort all topic-specific files
//            for (File file : topicFiles.values()) {
//                sortFileByTimestamp(file);
//            }
//
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void sortFileByTimestamp(File file) throws IOException {
//        if (!file.exists() || file.length() == 0) return;
//
//        ObjectNode root = (ObjectNode) mapper.readTree(file);
//        ArrayNode logsArray = (ArrayNode) root.get("logs");
//
//        if (logsArray == null || logsArray.isEmpty()) return;
//
//        // Convert to list and sort by timestamp
//        List<JsonNode> sortedLogs = StreamSupport.stream(logsArray.spliterator(), false)
//                .sorted(Comparator.comparingDouble(node -> node.get("timestamp").asDouble()))
//                .collect(Collectors.toList());
//
//        // Replace array with sorted version
//        ArrayNode newLogsArray = mapper.createArrayNode();
//        sortedLogs.forEach(newLogsArray::add);
//
//        root.set("logs", newLogsArray);
//
//        // Rewrite file with sorted logs
//        mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
//    }
//}
package com.example.logging_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
class KafkaLogConsumer {
    private final ObjectMapper mapper = new ObjectMapper();
    private final File globalFile = new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/events.json");

    // Map topics to their own file
    private final Map<String, File> topicFiles = new HashMap<>() {{
        put("coor-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/coor-service.json"));
        put("inventory-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/inventory-service.json"));
        put("payment-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/payment-service.json"));
        put("order-service", new File("/home/siddhesh/Documents/parent-project/logging-service/src/main/java/com/example/logging_service/data/order-service.json"));
    }};

    @KafkaListener(topics = {"coor-service","inventory-service","payment-service","order-service"}, groupId = "log-consumer-group")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            JsonNode eventNode = mapper.readTree(record.value());

            // Transform structure before writing
            ObjectNode transformed = transformEvent(eventNode);

            // Write to global file
            writeToFile(globalFile, transformed);

            // Write to topic-specific file
            File topicFile = topicFiles.get(record.topic());
            if (topicFile != null) {
                writeToFile(topicFile, transformed);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private ObjectNode transformEvent(JsonNode eventNode) {
        ObjectNode transformed = mapper.createObjectNode();

        // Extract top-level keys
        if (eventNode.has("correlationId"))
            transformed.set("correlationId", eventNode.get("correlationId"));
        if(eventNode.has("correlationid"))
            transformed.set("correlationid", eventNode.get("correlationid"));
        if (eventNode.has("eventype"))
            transformed.set("eventype", eventNode.get("eventype"));
        if (eventNode.has("timestamp"))
            transformed.set("timestamp", eventNode.get("timestamp"));

        // Move all remaining fields into "data"
        ObjectNode dataNode = mapper.createObjectNode();
        eventNode.fieldNames().forEachRemaining(field -> {
            if (!List.of("correlationId", "eventype", "timestamp","correlationid").contains(field)) {
                dataNode.set(field, eventNode.get(field));
            }
        });

        // Handle itemIds + quantity pairing
        if (dataNode.has("itemIds") && dataNode.has("quantity")
                && dataNode.get("itemIds").isArray() && dataNode.get("quantity").isArray()) {

            ArrayNode itemIds = (ArrayNode) dataNode.get("itemIds");
            ArrayNode quantities = (ArrayNode) dataNode.get("quantity");
            ArrayNode items = mapper.createArrayNode();

            int len = Math.min(itemIds.size(), quantities.size());
            for (int i = 0; i < len; i++) {
                ObjectNode item = mapper.createObjectNode();
                item.set("id", itemIds.get(i));
                item.set("quantity", quantities.get(i));
                items.add(item);
            }

            // Remove original arrays and add combined "items"
            dataNode.remove(List.of("itemIds", "quantity"));
            dataNode.set("items", items);
        }

        transformed.set("data", dataNode);
        return transformed;
    }

    private void writeToFile(File file, JsonNode eventNode) throws IOException {
        ObjectNode root;

        if (file.exists() && file.length() > 0) {
            root = (ObjectNode) mapper.readTree(file);
        } else {
            root = mapper.createObjectNode();
            root.putArray("logs");
        }

        ArrayNode logsArray = (ArrayNode) root.get("logs");
        logsArray.add(eventNode);

        mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
    }

    // ✅ Scheduled independent sorting task (runs every 60 seconds)
    @Scheduled(fixedDelay = 1000)
    public void scheduledSortTask() {
        try {
            sortFileByTimestamp(globalFile);
            for (File file : topicFiles.values()) {
                sortFileByTimestamp(file);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sortFileByTimestamp(File file) throws IOException {
        if (!file.exists() || file.length() == 0) return;

        ObjectNode root = (ObjectNode) mapper.readTree(file);
        ArrayNode logsArray = (ArrayNode) root.get("logs");
        if (logsArray == null || logsArray.isEmpty()) return;

        List<JsonNode> sortedLogs = StreamSupport.stream(logsArray.spliterator(), false)
                .sorted(Comparator.comparingDouble(node -> node.get("timestamp").asDouble()))
                .collect(Collectors.toList());

        ArrayNode newLogsArray = mapper.createArrayNode();
        sortedLogs.forEach(newLogsArray::add);
        root.set("logs", newLogsArray);

        mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
    }
}
