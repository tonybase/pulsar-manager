/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pulsar.manager.controller;

import com.google.common.collect.Maps;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.BatchMessageIdImpl;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.manager.service.EnvironmentCacheService;
import org.apache.pulsar.manager.service.TopicsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.hibernate.validator.constraints.Range;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Topics rest api class.
 */
@RequestMapping(value = "/pulsar-manager/admin/v2")
@Api(description = "Support more flexible queries to namespaces.")
@Validated
@RestController
public class TopicsController {

    private final TopicsService topicsService;
    private final EnvironmentCacheService environmentCacheService;
    private final HttpServletRequest request;

    @Value("${backend.jwt.token}")
    private String token;

    @Value("${pulsar.peek.message}")
    private boolean peekMessage;

    @Value("${tls.enabled}")
    private boolean tlsEnabled;

    @Value("${tls.hostname.verifier}")
    private boolean tlsHostnameVerifier;

    @Value("${tls.pulsar.admin.ca-certs}")
    private String tlsPulsarAdminCaCerts;

    @Autowired
    public TopicsController(
            TopicsService topicsService,
            EnvironmentCacheService environmentCacheService,
            HttpServletRequest request) {
        this.topicsService = topicsService;
        this.environmentCacheService = environmentCacheService;
        this.request = request;
    }

    @ApiOperation(value = "Query topic info by tenant and namespace")
    @ApiResponses({
            @ApiResponse(code = 200, message = "ok"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @RequestMapping(value = "/topics/{tenant}/{namespace}", method = RequestMethod.GET)
    public Map<String, Object> getTopicsByTenantNamespace(
            @ApiParam(value = "page_num", defaultValue = "1", example = "1")
            @RequestParam(name = "page_num", defaultValue = "1")
            @Min(value = 1, message = "page_num is incorrect, should be greater than 0.")
                    Integer pageNum,
            @ApiParam(value = "page_size", defaultValue = "10", example = "10")
            @RequestParam(name="page_size", defaultValue = "10")
            @Range(min = 1, max = 1000, message = "page_size is incorrect, should be greater than 0 and less than 1000.")
                    Integer pageSize,
            @ApiParam(value = "The name of tenant")
            @Size(min = 1, max = 255)
            @PathVariable String tenant,
            @ApiParam(value = "The name of namespace")
            @Size(min = 1, max = 255)
            @PathVariable String namespace) {
        String requestHost = environmentCacheService.getServiceUrl(request);
        return topicsService.getTopicsList(pageNum, pageSize, tenant, namespace, requestHost);
    }

    @ApiOperation(value = "Query topic stats info by tenant and namespace")
    @ApiResponses({
            @ApiResponse(code = 200, message = "ok"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @RequestMapping(value = "/topics/{tenant}/{namespace}/stats", method = RequestMethod.GET)
    public Map<String, Object> getTopicsStats(
            @ApiParam(value = "page_num", defaultValue = "1", example = "1")
            @RequestParam(name = "page_num", defaultValue = "1")
            @Min(value = 1, message = "page_num is incorrect, should be greater than 0.")
                    Integer pageNum,
            @ApiParam(value = "page_size", defaultValue = "10", example = "10")
            @RequestParam(name="page_size", defaultValue = "10")
            @Range(min = 1, max = 1000, message = "page_size is incorrect, should be greater than 0 and less than 1000.")
                    Integer pageSize,
            @ApiParam(value = "The name of tenant")
            @Size(min = 1, max = 255)
            @PathVariable String tenant,
            @ApiParam(value = "The name of namespace")
            @Size(min = 1, max = 255)
            @PathVariable String namespace) {
        String env = request.getHeader("environment");
        String serviceUrl = environmentCacheService.getServiceUrl(request);
        return topicsService.getTopicStats(
            pageNum, pageSize,
            tenant, namespace,
            env, serviceUrl);
    }

    @ApiOperation(value = "Peek messages from pulsar broker")
    @ApiResponses({
            @ApiResponse(code = 200, message = "ok"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @RequestMapping(
            value = "/{persistent}/{tenant}/{namespace}/{topic}/subscription/{subName}/{messagePosition}",
            method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> peekMessages(
            @PathVariable String persistent,
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic,
            @PathVariable String subName,
            @PathVariable Integer messagePosition) {
        String requestHost = environmentCacheService.getServiceUrl(request);
        Map<String, Object> result = Maps.newHashMap();
        if (!peekMessage) {
            result.put("error", "If you want to support peek message," +
                    "turn on option pulsar.peek.message in file application.properties");
            return ResponseEntity.ok(result);
        }
        PulsarAdmin pulsarAdmin = null;
        // to do check permission for non super, waiting for https://github.com/apache/pulsar-manager/pull/238
        try {
            PulsarAdminBuilder pulsarAdminBuilder = PulsarAdmin.builder();
            if (tlsEnabled) {
                pulsarAdminBuilder
                        .tlsTrustCertsFilePath(tlsPulsarAdminCaCerts)
                        .enableTlsHostnameVerification(tlsHostnameVerifier);
            }
            if (token != null && token.length() > 0) {
                pulsarAdminBuilder.authentication(AuthenticationFactory.token(token));
            }
            pulsarAdmin = pulsarAdminBuilder.serviceHttpUrl(requestHost).build();
            String topicFullPath = persistent + "://" + tenant + "/" + namespace + "/" + topic;
            List<Message<byte[]>> messages = pulsarAdmin.topics().peekMessages(topicFullPath, subName, messagePosition);
            List<Map<String, Object>> mapList = new ArrayList<>();
            for (Message<byte[]> msg: messages) {
                Map<String, Object> message = Maps.newHashMap();
                if (msg.getMessageId() instanceof BatchMessageIdImpl) {
                    BatchMessageIdImpl msgId = (BatchMessageIdImpl) msg.getMessageId();
                    message.put("ledgerId", msgId.getLedgerId());
                    message.put("entryId", msgId.getEntryId());
                    message.put("batchIndex", msgId.getBatchIndex());
                    message.put("batch", true);
                } else {
                    MessageIdImpl msgId = (MessageIdImpl) msg.getMessageId();
                    message.put("batch", false);
                    message.put("ledgerId", msgId.getLedgerId());
                    message.put("entryId", msgId.getEntryId());
                }
                if (msg.getProperties().size() > 0) {
                    msg.getProperties().forEach((k, v) -> {
                        message.put(k, v);
                    });
                }
                message.put("data", msg.getData());
                mapList.add(message);
            }
            result.put("data", mapList);
        } catch (PulsarClientException clientException) {
            result.put("error", clientException.getMessage());
        } catch (PulsarAdminException adminException) {
            result.put("error", adminException.getMessage());
        }
        if (pulsarAdmin != null) {
            pulsarAdmin.close();
        }
        return ResponseEntity.ok(result);
    }
}