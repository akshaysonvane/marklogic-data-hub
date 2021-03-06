/*
 * Copyright (c) 2021 MarkLogic Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.marklogic.hub.central.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.hub.DatabaseKind;
import com.marklogic.hub.dataservices.JobService;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping(value = "/api/jobs")
public class JobController extends BaseController {

    @RequestMapping(value = "/{jobId}", method = RequestMethod.GET)
    @ResponseBody
    @ApiOperation(value = "Get the Job document associated with the given ID", response = Job.class)
    public JsonNode getJob(@PathVariable String jobId) {
        JsonNode jobsJson = JobService.on(getHubClient().getJobsClient()).getJobWithDetails(jobId);
        if (jobsJson == null) {
            return null;
        }

        return flattenJobsJson(jobsJson);
    }

    @RequestMapping(value = "/stepResponses", method = RequestMethod.POST)
    @ResponseBody
    @ApiOperation(value = "Get Step Responses from the Job documents", response = StepResponses.class)
    @Secured("ROLE_jobMonitor")
    public ResponseEntity<JsonNode> findStepResponses(@RequestBody JsonNode query) {
        JsonNode stepResponses = JobService.on(getHubClient().getJobsClient()).findStepResponses(query);
        return new ResponseEntity<>(stepResponses, HttpStatus.OK);
    }

    @RequestMapping(value = "/stepResponses/facetValues", method = RequestMethod.POST)
    @ResponseBody
    @ApiOperation(value = "Get an array of strings that match the pattern for a given property")
    @Secured("ROLE_jobMonitor")
    public ResponseEntity<JsonNode> getFacetValues(@RequestBody JsonNode fsQuery) {
        JsonNode facetValues = JobService.on(getHubClient().getJobsClient()).getMatchingPropertyValues(fsQuery);
        return new ResponseEntity<>(facetValues, HttpStatus.OK);
    }

    private JsonNode flattenJobsJson(JsonNode jobJSON) {
        if (jobJSON.isArray()) {
            ArrayNode array = new ObjectMapper().createArrayNode();
            jobJSON.elements().forEachRemaining((json) -> {
                array.add(flattenJobsJson(json));
            });
            return array;
        } else if (jobJSON.has("job")) {
            return processTargetDatabase(jobJSON.get("job"));
        } else {
            return jobJSON;
        }
    }

    /**
     * Modifies the passed in JsonNode to replace the targetDatabase name with the Database kind
     * so the FE can set the correct database for data exploration.
     * @param jobNode
     * @return - Modified JsonNode
     */
    protected JsonNode processTargetDatabase(JsonNode jobNode) {
        Optional.of(jobNode)
                .map(node -> node.get("stepResponses"))
                .ifPresent(stepResponsesNode -> {
                    stepResponsesNode.forEach(stepResponseNode -> {
                        String targetDatabase = Optional.ofNullable(stepResponseNode.get("targetDatabase"))
                                .map(JsonNode::asText)
                                .orElse(null);
                        if (getHubClient().getDbName(DatabaseKind.STAGING).equals(targetDatabase)) {
                            ((ObjectNode) stepResponseNode).put("targetDatabase", "staging");
                        }
                        else if ((getHubClient().getDbName(DatabaseKind.FINAL).equals(targetDatabase))) {
                            ((ObjectNode) stepResponseNode).put("targetDatabase", "final");
                        }
                    });
                });

        return jobNode;
    }

    public static class Job {
        public String jobId;
        public String flowId;
        public String startTime;
        public String endTime;
        public Map<String, Object> steps;
        public String user;
        @ApiModelProperty("Name of the flow being processed")
        public String flow;
        @ApiModelProperty("ID of Entity Type related to the Job")
        public String targetEntityType;
        public Integer lastAttemptedStep;
        public Integer lastCompletedStep;
        public String status;
        public Integer successfulEvents;
        public Integer failedEvents;
    }

    public static class StepResponseResult {
        public String stepName;
        public String stepDefinitionType;
        public String status;
        public String entityName;
        public String startTime;
        public String duration;
        public String successfulItemCount;
        public String failedItemCount;
        public String user;
        public String jobId;
        public String flowName;
    }

    public static class StepResponses {
        public Long total;
        public int start;
        public int pageLength;
        public List<StepResponseResult> results;
    }
}
