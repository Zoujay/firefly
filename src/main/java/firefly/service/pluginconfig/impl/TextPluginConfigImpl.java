package firefly.service.pluginconfig.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.Gson;
import firefly.bean.dto.AbstractPluginDto;
import firefly.bean.dto.TextPluginConfigDto;
import firefly.constant.PluginType;
import firefly.dao.pluginconfig.ITextPluginConfigDao;
import firefly.model.plugin.TextPluginModel;
import firefly.service.pluginconfig.IPluginConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class TextPluginConfigImpl implements IPluginConfig {


    @Autowired
    private ITextPluginConfigDao textPluginConfigDao;

    @Override
    public PluginType getPluginType() {
        return PluginType.TEXT;
    }


    public TextPluginConfigDto parseJobConfigRequest(JsonNode pluginRaw, Long JobID) {
        Gson gson = new Gson();
        TextPluginConfigDto pluginConfigDto = gson.fromJson(pluginRaw.toString(), TextPluginConfigDto.class);
        pluginConfigDto.setJobID(JobID);
        return pluginConfigDto;
    }

    public TextPluginModel parsePluginRequest(JsonNode pluginRaw, Long jobID) {
        TextPluginConfigDto request = this.parseJobConfigRequest(pluginRaw, jobID);
        TextPluginModel model = new TextPluginModel();
        model.setText(request.getText());
        model.setJobID(jobID);
        return model;
    }

    @Override
    public Long savePlugin(JsonNode pluginRaw, Long jobID) {
        TextPluginModel model = this.parsePluginRequest(pluginRaw, jobID);
        textPluginConfigDao.save(model);
        return model.getId();
    }

    @Override
    public AbstractPluginDto getPlugin(Long id) {
        return getTextPluginConfigDtoById(id);
    }


    public TextPluginConfigDto getTextPluginConfigDtoById(Long id) {
        Optional<TextPluginModel> textPluginModel = textPluginConfigDao.findById(id);
        return textPluginModel.map(this::assembleTextPluginConfigDto).orElse(null);
    }

    public TextPluginConfigDto assembleTextPluginConfigDto(TextPluginModel textPluginModel) {
        TextPluginConfigDto textPluginConfigDto = new TextPluginConfigDto();
        textPluginConfigDto.setText(textPluginModel.getText());
        textPluginConfigDto.setID(textPluginModel.getId());
        return textPluginConfigDto;
    }
}
