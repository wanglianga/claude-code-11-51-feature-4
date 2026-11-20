package com.semantic.platform.api;

import com.semantic.platform.metric.MetricDef;
import com.semantic.platform.metric.MetricService;
import com.semantic.platform.metric.MetricVersion;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final MetricService metricService;

    public MetricController(MetricService metricService) {
        this.metricService = metricService;
    }

    @GetMapping
    public List<MetricDef> list() {
        return metricService.listAll();
    }

    @GetMapping("/{key}/versions")
    public List<MetricVersion> versions(@PathVariable String key) {
        return metricService.versions(key);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) {
        String metricKey = (String) body.get("metricKey");
        Object defObj = body.get("definition");
        String definition;
        try {
            definition = defObj instanceof String s ? s
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(defObj);
        } catch (Exception e) {
            definition = String.valueOf(defObj);
        }
        String grain = body.get("grain") == null ? "day" : (String) body.get("grain");
        return metricService.validateWithGrain(metricKey, definition, grain);
    }

    @PostMapping
    public MetricVersion create(@RequestBody Map<String, String> body,
                                @RequestAttribute(name = "user", required = false) String user) {
        return metricService.saveDraft(
                body.get("metricKey"), body.get("name"), body.get("description"),
                body.getOrDefault("grain", "day"), body.get("definition"),
                user == null ? "system" : user);
    }

    @PostMapping("/{key}/publish")
    public MetricVersion publish(@PathVariable String key, @RequestBody Map<String, Integer> body,
                                 @RequestAttribute(name = "user", required = false) String user) {
        return metricService.publish(key, body.get("version"), user == null ? "system" : user);
    }

    @PostMapping("/{key}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String key,
                                        @RequestAttribute(name = "user", required = false) String user) {
        return metricService.withdraw(key, user == null ? "system" : user);
    }

    @GetMapping("/{key}/impact")
    public Map<String, Object> impact(@PathVariable String key) {
        return metricService.impact(key);
    }

    @GetMapping("/{key}/published")
    public MetricVersion published(@PathVariable String key) {
        return metricService.publishedVersion(key);
    }
}
