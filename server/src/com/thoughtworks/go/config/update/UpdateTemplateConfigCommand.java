/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
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

package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterialConfig;
import com.thoughtworks.go.i18n.LocalizedMessage;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.EntityHashingService;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.result.LocalizedOperationResult;
import com.thoughtworks.go.serverhealth.HealthStateType;

import java.util.ArrayList;
import java.util.List;

public class UpdateTemplateConfigCommand extends TemplateConfigCommand {
    private final PipelineTemplateConfig newTemplateConfig;
    private PipelineTemplateConfig existingTemplateConfig;
    private String md5;
    private EntityHashingService entityHashingService;

    public UpdateTemplateConfigCommand(PipelineTemplateConfig templateConfig, Username currentUser, GoConfigService goConfigService, LocalizedOperationResult result, String md5, EntityHashingService entityHashingService) {
        super(templateConfig, result, currentUser, goConfigService);
        this.newTemplateConfig = templateConfig;
        this.md5 = md5;
        this.entityHashingService = entityHashingService;
    }

    @Override
    public void update(CruiseConfig modifiedConfig) throws Exception {
        this.existingTemplateConfig = findAddedTemplate(modifiedConfig);
        templateConfig.setAuthorization(existingTemplateConfig.getAuthorization());
        TemplatesConfig templatesConfig = modifiedConfig.getTemplates();
        templatesConfig.removeTemplateNamed(existingTemplateConfig.name());
        templatesConfig.add(templateConfig);
        modifiedConfig.setTemplates(templatesConfig);
    }

    @Override
    public boolean isValid(CruiseConfig preprocessedConfig) {
        boolean isValid = validateElasticProfileId(preprocessedConfig);
        return isValid && super.isValid(preprocessedConfig, false);
    }

    private boolean validateElasticProfileId(CruiseConfig preprocessedConfig) {
        ArrayList<String> changedElasticProfileId = getChangedElasticProfileIds();
        if (changedElasticProfileId.isEmpty()) {
            return true;
        }

        List<CaseInsensitiveString> pipelinesUsingCurrentTemplate = preprocessedConfig.pipelinesAssociatedWithTemplate(templateConfig.name());
        if (pipelinesUsingCurrentTemplate.isEmpty()) {
            return true;
        }

        ConfigSaveValidationContext context = ConfigSaveValidationContext.forChain(preprocessedConfig);

        for (String elasticProfileId : changedElasticProfileId) {
            if (!context.isValidProfileId(elasticProfileId)) {
                String message = String.format("No profile defined corresponding to profile_id '%s'", elasticProfileId);
                newTemplateConfig.addError("ELASTIC_PROFILE_ID", message);
                return false;
            }
        }

        return true;
    }

    private ArrayList<String> getChangedElasticProfileIds() {
        ArrayList<String> changedElasticProfileId = new ArrayList<>();
        for (StageConfig stageConfig : existingTemplateConfig.getStages()) {
            StageConfig newTemplateStage = templateConfig.getStage(stageConfig.name());
            if (newTemplateStage != null) {
                for (JobConfig jobConfig : stageConfig.getJobs()) {
                    JobConfig newTemplateJob = newTemplateStage.jobConfigByConfigName(jobConfig.name());
                    if (newTemplateJob != null) {
                        String elasticProfileId = jobConfig.getElasticProfileId();
                        if ((elasticProfileId != newTemplateJob.getElasticProfileId())) {
                            changedElasticProfileId.add(newTemplateJob.getElasticProfileId());
                        }
                    }
                }
            }
        }
        return changedElasticProfileId;
    }

    @Override
    public boolean canContinue(CruiseConfig cruiseConfig) {
        return isUserAuthorized() && isRequestFresh(cruiseConfig);
    }

    private boolean isUserAuthorized() {
        if (!goConfigService.isAuthorizedToEditTemplate(templateConfig.name().toString(), currentUser)) {
            result.unauthorized(LocalizedMessage.string("UNAUTHORIZED_TO_EDIT"), HealthStateType.unauthorised());
            return false;
        }
        return true;
    }

    private boolean isRequestFresh(CruiseConfig cruiseConfig) {
        PipelineTemplateConfig pipelineTemplateConfig = findAddedTemplate(cruiseConfig);
        boolean freshRequest = entityHashingService.md5ForEntity(pipelineTemplateConfig).equals(md5);
        if (!freshRequest) {
            result.stale(LocalizedMessage.string("STALE_RESOURCE_CONFIG", "Template", templateConfig.name()));
        }

        return freshRequest;
    }

    public ArrayList<CaseInsensitiveString> getUpdatedStageNames() {
        ArrayList<CaseInsensitiveString> modifiedStages = new ArrayList<>();
        for (StageConfig stageConfig : existingTemplateConfig.getStages()) {
            CaseInsensitiveString name = stageConfig.name();
            if (newTemplateConfig.getStage(name) == null) {
                modifiedStages.add(name);
            }
        }
        return modifiedStages;
    }
}

