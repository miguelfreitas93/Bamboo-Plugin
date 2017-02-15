package com.cx.plugin;

/**
 * Created by galn on 20/12/2016.
 */

import com.atlassian.bamboo.collections.ActionParametersMap;
import com.atlassian.bamboo.configuration.AdministrationConfiguration;
import com.atlassian.bamboo.security.EncryptionException;
import com.atlassian.bamboo.security.EncryptionServiceImpl;
import com.atlassian.bamboo.task.AbstractTaskConfigurator;
import com.atlassian.bamboo.task.TaskDefinition;
import com.atlassian.bamboo.utils.error.ErrorCollection;
import com.atlassian.bamboo.ww2.actions.build.admin.config.task.ConfigureBuildTasks;
import com.atlassian.spring.container.ContainerManager;
import com.atlassian.util.concurrent.Nullable;
import com.checkmarx.v7.ArrayOfGroup;
import com.checkmarx.v7.Group;
import com.cx.client.CxClientService;
import com.cx.client.CxClientServiceImpl;
import com.cx.client.exception.CxClientException;
import com.cx.plugin.dto.ValueComparator;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import static com.cx.plugin.dto.CxParam.*;

public class AgentTaskConfigurator extends AbstractTaskConfigurator {
    private static Map<String, String> presetList = new HashMap<String, String>();
    private static Map<String, String> teamPathList = new HashMap<String, String>();
    private CxClientService cxClientService = null;
    private final static String DEFAULT_SETTING_LABEL = "Use Global Setting";
    private final static String SPECIFIC_SETTING_LABEL = "Specific Task Setting";
    private final static String DEFAULT_SERVER_URL = "http://";
    private final static String NO_PRESET_MESSAGE = "Provide the correct Checkmarx server credentials to see presets list";
    private final static String NO_TEAM_MESSAGE = "Provide the correct Checkmarx server credentials to see teams list";
    private final static String OPTION_TRUE = "true";
    private final static String OPTION_FALSE = "false";

    private static AdministrationConfiguration adminConfig;
    private static Map<String, String> CONFIGURATION_MODE_TYPES_MAP_SERVER = ImmutableMap.of(GLOBAL_CONFIGURATION_SERVER, DEFAULT_SETTING_LABEL, COSTUME_CONFIGURATION_SERVER, SPECIFIC_SETTING_LABEL);
    private static Map<String, String> CONFIGURATION_MODE_TYPES_MAP_CXSAST = ImmutableMap.of(GLOBAL_CONFIGURATION_CXSAST, DEFAULT_SETTING_LABEL, COSTUME_CONFIGURATION_CXSAST, SPECIFIC_SETTING_LABEL);
    private static Map<String, String> CONFIGURATION_MODE_TYPES_MAP_CONTROL = ImmutableMap.of(GLOBAL_CONFIGURATION_CONTROL, DEFAULT_SETTING_LABEL, COSTUME_CONFIGURATION_CONTROL, SPECIFIC_SETTING_LABEL);
    public static final Logger log = LoggerFactory.getLogger(AgentTaskConfigurator.class);

    @NotNull
    @Override
    public Map<String, String> generateTaskConfigMap(@NotNull final ActionParametersMap params, @Nullable final TaskDefinition previousTaskDefinition) {

        Map<String, String> config = super.generateTaskConfigMap(params, previousTaskDefinition);
        config = generateCredentialsFields(params, config);

        config.put(PROJECT_NAME, params.getString(PROJECT_NAME));
        config.put(GENERATE_PDF_REPORT, params.getString(GENERATE_PDF_REPORT));

        String cxPreset = params.getString(PRESET_ID);
        if (!(NO_PRESET).equals(cxPreset) && presetList != null) {
            config.put(PRESET_NAME, presetList.get(cxPreset));
            config.put(PRESET_ID, cxPreset);
        }

        String cxTeam = params.getString(TEAM_PATH_ID);
        if (!NO_TEAM_PATH.equals(cxTeam) && teamPathList != null) {
            config.put(TEAM_PATH_NAME, teamPathList.get(cxTeam));
            config.put(TEAM_PATH_ID, cxTeam);
        }

        config.put(OSA_ENABLED, params.getString(OSA_ENABLED));
        config.put(IS_INCREMENTAL, params.getString(IS_INCREMENTAL));
        config.put(IS_SYNCHRONOUS, params.getString(IS_SYNCHRONOUS));

        //save 'CxSAST Scan' fields
        config = generateCxSASTFields(params, adminConfig, config);

        //save Scan Control  fields
        config = generateScanControlFields(params, adminConfig, config);

        return config;
    }

    @Override
    public void populateContextForCreate(@NotNull final Map<String, Object> context) {
        super.populateContextForCreate(context);
        context.put("configurationModeTypesServer", CONFIGURATION_MODE_TYPES_MAP_SERVER);
        context.put("configurationModeTypesCxSAST", CONFIGURATION_MODE_TYPES_MAP_CXSAST);
        context.put("configurationModeTypesControl", CONFIGURATION_MODE_TYPES_MAP_CONTROL);
        String projectName = resolveProjectName(context);
        context.put(PROJECT_NAME, projectName);
        context.put(SERVER_URL, DEFAULT_SERVER_URL);

        populateCredentialsFieldsForCreate(context);

        populateCxSASTFields(context, null, true);

        context.put(IS_INCREMENTAL, OPTION_FALSE);
        populateScanControlFields(context, null, true);

        context.put(IS_SYNCHRONOUS, OPTION_TRUE);
        context.put(GENERATE_PDF_REPORT, OPTION_FALSE);
        context.put(OSA_ENABLED, OPTION_FALSE);
    }

    private String resolveProjectName(@NotNull Map<String, Object> context) {
        String projectName;
        try {
            Object plan = context.get("plan");
            Method getName = plan.getClass().getDeclaredMethod("getName");
            projectName = (String) getName.invoke(plan);
        } catch (Exception e) {
            projectName = "";
        }
        return projectName;
    }

    private void populateCredentialsFieldsForCreate(final Map<String, Object> context) {
        String cxServerUrl = getAdminConfig(GLOBAL_SERVER_URL);
        String cxUser = getAdminConfig(GLOBAL_USER_NAME);
        String cxPass = getAdminConfig(GLOBAL_PASSWORD);

        context.put(SERVER_URL, cxServerUrl);
        context.put(USER_NAME, cxUser);
        context.put(PASSWORD, cxPass);
        context.put(GLOBAL_SERVER_URL, cxServerUrl);
        context.put(GLOBAL_USER_NAME, cxUser);
        context.put(GLOBAL_PASSWORD, cxPass);
        context.put(GLOBAL_SERVER_CREDENTIALS, GLOBAL_CONFIGURATION_SERVER);

        populateTeamAndPresetFields(cxServerUrl, cxUser, cxPass, null, null, context);
    }

    @Override
    public void populateContextForEdit(@NotNull final Map<String, Object> context, @NotNull final TaskDefinition taskDefinition) {

        super.populateContextForEdit(context, taskDefinition);
        Map<String, String> configMap = taskDefinition.getConfiguration();

        context.put("configurationModeTypesServer", CONFIGURATION_MODE_TYPES_MAP_SERVER);
        context.put("configurationModeTypesCxSAST", CONFIGURATION_MODE_TYPES_MAP_CXSAST);
        context.put("configurationModeTypesControl", CONFIGURATION_MODE_TYPES_MAP_CONTROL);
        context.put(PROJECT_NAME, configMap.get(PROJECT_NAME));

        populateCredentialsFieldsForEdit(context, configMap);

        populateCxSASTFields(context, configMap, false);
        context.put(IS_INCREMENTAL, configMap.get(IS_INCREMENTAL));
        context.put(GENERATE_PDF_REPORT, configMap.get(GENERATE_PDF_REPORT));
        context.put(OSA_ENABLED, configMap.get(OSA_ENABLED));

        populateScanControlFields(context, configMap, false);
    }

    private void populateCredentialsFieldsForEdit(@NotNull final Map<String, Object> context, Map<String, String> configMap) {
        String cxServerUrl;
        String cxUser;
        String cxPass;
        String configType = configMap.get(GLOBAL_SERVER_CREDENTIALS);

        if ((GLOBAL_CONFIGURATION_SERVER.equals(configType))) {
            cxServerUrl = getAdminConfig(GLOBAL_SERVER_URL);
            cxUser = getAdminConfig(GLOBAL_USER_NAME);
            cxPass = getAdminConfig(GLOBAL_PASSWORD);

        } else {
            cxServerUrl = configMap.get(SERVER_URL);
            cxUser = configMap.get(USER_NAME);
            cxPass = configMap.get(PASSWORD);
        }

        context.put(SERVER_URL, configMap.get(SERVER_URL));
        context.put(USER_NAME, configMap.get(USER_NAME));
        context.put(PASSWORD, configMap.get(PASSWORD));
        context.put(GLOBAL_SERVER_URL, getAdminConfig(GLOBAL_SERVER_URL));
        context.put(GLOBAL_USER_NAME, getAdminConfig(GLOBAL_USER_NAME));
        context.put(GLOBAL_PASSWORD, getAdminConfig(GLOBAL_PASSWORD));
        context.put(GLOBAL_SERVER_CREDENTIALS, configType);

        String cxPreset = configMap.get(PRESET_ID);
        String cxTeam = configMap.get(TEAM_PATH_ID);

        populateTeamAndPresetFields(cxServerUrl, cxUser, cxPass, cxPreset, cxTeam, context);
    }


    private void populateCxSASTFields(@NotNull final Map<String, Object> context, Map<String, String> configMap, boolean forCreate) {
        if (forCreate) {
            context.put(GLOBAL_CXSAST, GLOBAL_CONFIGURATION_CXSAST);
            context.put(FOLDER_EXCLUSION, getAdminConfig(GLOBAL_FOLDER_EXCLUSION));
            String globalFilterPattern = getAdminConfig(GLOBAL_FILTER_PATTERN);
            String filterPattern = StringUtils.isEmpty(globalFilterPattern) ? DEFAULT_FILTER_PATTERNS : globalFilterPattern;
            context.put(FILTER_PATTERN, filterPattern);
            context.put(SCAN_TIMEOUT_IN_MINUTES, getAdminConfig(GLOBAL_SCAN_TIMEOUT_IN_MINUTES));
        } else {
            context.put(GLOBAL_CXSAST, configMap.get(GLOBAL_CXSAST));
            context.put(FOLDER_EXCLUSION, configMap.get(FOLDER_EXCLUSION));
            context.put(FILTER_PATTERN, configMap.get(FILTER_PATTERN));
            context.put(SCAN_TIMEOUT_IN_MINUTES, configMap.get(SCAN_TIMEOUT_IN_MINUTES));
        }

        context.put(GLOBAL_FILTER_PATTERN, getAdminConfig(GLOBAL_FILTER_PATTERN));
        context.put(GLOBAL_FOLDER_EXCLUSION, getAdminConfig(GLOBAL_FOLDER_EXCLUSION));
        context.put(GLOBAL_FILTER_PATTERN, getAdminConfig(GLOBAL_FILTER_PATTERN));
        context.put(GLOBAL_SCAN_TIMEOUT_IN_MINUTES, getAdminConfig(GLOBAL_SCAN_TIMEOUT_IN_MINUTES));
    }


    private void populateScanControlFields(@NotNull final Map<String, Object> context, Map<String, String> configMap, boolean forCreate) {
        if (forCreate) {
            context.put(GLOBAL_SCAN_CONTROL, GLOBAL_CONFIGURATION_CONTROL);
            context.put(IS_SYNCHRONOUS, getAdminConfig(GLOBAL_IS_SYNCHRONOUS));
            context.put(THRESHOLDS_ENABLED, getAdminConfig(GLOBAL_THRESHOLDS_ENABLED));
            context.put(HIGH_THRESHOLD, getAdminConfig(GLOBAL_HIGH_THRESHOLD));
            context.put(MEDIUM_THRESHOLD, getAdminConfig(GLOBAL_MEDIUM_THRESHOLD));
            context.put(LOW_THRESHOLD, getAdminConfig(GLOBAL_LOW_THRESHOLD));
            context.put(OSA_THRESHOLDS_ENABLED, getAdminConfig(GLOBAL_OSA_THRESHOLDS_ENABLED));
            context.put(OSA_HIGH_THRESHOLD, getAdminConfig(GLOBAL_OSA_HIGH_THRESHOLD));
            context.put(OSA_MEDIUM_THRESHOLD, getAdminConfig(GLOBAL_OSA_MEDIUM_THRESHOLD));
            context.put(OSA_LOW_THRESHOLD, getAdminConfig(GLOBAL_OSA_LOW_THRESHOLD));
        } else {
            context.put(GLOBAL_SCAN_CONTROL, configMap.get(GLOBAL_SCAN_CONTROL));
            context.put(IS_SYNCHRONOUS, configMap.get(IS_SYNCHRONOUS));
            context.put(THRESHOLDS_ENABLED, configMap.get(THRESHOLDS_ENABLED));
            context.put(HIGH_THRESHOLD, configMap.get(HIGH_THRESHOLD));
            context.put(MEDIUM_THRESHOLD, configMap.get(MEDIUM_THRESHOLD));
            context.put(LOW_THRESHOLD, configMap.get(LOW_THRESHOLD));
            context.put(OSA_THRESHOLDS_ENABLED, configMap.get(OSA_THRESHOLDS_ENABLED));
            context.put(OSA_HIGH_THRESHOLD, configMap.get(OSA_HIGH_THRESHOLD));
            context.put(OSA_MEDIUM_THRESHOLD, configMap.get(OSA_MEDIUM_THRESHOLD));
            context.put(OSA_LOW_THRESHOLD, configMap.get(OSA_LOW_THRESHOLD));
        }

        context.put(GLOBAL_IS_SYNCHRONOUS, getAdminConfig(GLOBAL_IS_SYNCHRONOUS));
        context.put(GLOBAL_THRESHOLDS_ENABLED, getAdminConfig(GLOBAL_THRESHOLDS_ENABLED));
        context.put(GLOBAL_HIGH_THRESHOLD, getAdminConfig(GLOBAL_HIGH_THRESHOLD));
        context.put(GLOBAL_MEDIUM_THRESHOLD, getAdminConfig(GLOBAL_MEDIUM_THRESHOLD));
        context.put(GLOBAL_LOW_THRESHOLD, getAdminConfig(GLOBAL_LOW_THRESHOLD));
        context.put(GLOBAL_OSA_THRESHOLDS_ENABLED, getAdminConfig(GLOBAL_OSA_THRESHOLDS_ENABLED));
        context.put(GLOBAL_OSA_HIGH_THRESHOLD, getAdminConfig(GLOBAL_OSA_HIGH_THRESHOLD));
        context.put(GLOBAL_OSA_MEDIUM_THRESHOLD, getAdminConfig(GLOBAL_OSA_MEDIUM_THRESHOLD));
        context.put(GLOBAL_OSA_LOW_THRESHOLD, getAdminConfig(GLOBAL_OSA_LOW_THRESHOLD));
    }

    private void populateTeamAndPresetFields(final String serverUrl, final String userName, final String password, String preset, String teamPath, @NotNull final Map<String, Object> context) {
        try {
            //the method initialized the CxClient service
            if (tryLogin(userName, password, serverUrl)) {

                presetList = convertPresetType(cxClientService.getPresetList());
                context.put(PRESET_LIST, presetList);
                if (!StringUtils.isEmpty(preset)) {
                    context.put(PRESET_ID, preset);
                } else if (!presetList.isEmpty()) {
                    context.put(PRESET_ID, presetList.entrySet().iterator().next());
                }

                teamPathList = convertTeamPathType(cxClientService.getAssociatedGroupsList());
                context.put(TEAM_PATH_LIST, teamPathList);
                if (!StringUtils.isEmpty(teamPath)) {
                    context.put(TEAM_PATH_ID, teamPath);
                } else if (!teamPathList.isEmpty())
                    context.put(TEAM_PATH_ID, teamPathList.entrySet().iterator().next());

            } else {

                final Map<String, String> noPresets = new HashMap<String, String>();
                noPresets.put(NO_PRESET, NO_PRESET_MESSAGE);
                context.put(PRESET_LIST, noPresets);

                final Map<String, String> noTeams = new HashMap<String, String>();
                noTeams.put(NO_TEAM_PATH, NO_TEAM_MESSAGE);
                context.put(TEAM_PATH_LIST, noTeams);
            }
        } catch (Exception e) {
            log.warn("Exception caught during populateTeamAndPresetFields: '" + e.getMessage() + "'", e);
        }
    }

    private Map<String, String> generateCredentialsFields(@NotNull final ActionParametersMap params, Map<String, String> config) {
        final String configType = params.getString(GLOBAL_SERVER_CREDENTIALS);
        config.put(GLOBAL_SERVER_CREDENTIALS, configType);

        if (COSTUME_CONFIGURATION_SERVER.equals(configType)) {
            config.put(SERVER_URL, params.getString(SERVER_URL));
            config.put(USER_NAME, params.getString(USER_NAME));
            config.put(PASSWORD, encrypt(params.getString(PASSWORD)));
        }
        return config;
    }

    private Map<String, String> generateCxSASTFields(@NotNull final ActionParametersMap params, final AdministrationConfiguration adminConfig, Map<String, String> config) {

        final String configType = params.getString(GLOBAL_CXSAST);
        config.put(GLOBAL_CXSAST, configType);

        if (COSTUME_CONFIGURATION_CXSAST.equals(configType)) {
            config.put(FOLDER_EXCLUSION, params.getString(FOLDER_EXCLUSION));
            config.put(FILTER_PATTERN, params.getString(FILTER_PATTERN));
            config.put(SCAN_TIMEOUT_IN_MINUTES, params.getString(SCAN_TIMEOUT_IN_MINUTES));
        }
        return config;
    }

    private Map<String, String> generateScanControlFields(@NotNull final ActionParametersMap params, final AdministrationConfiguration adminConfig, Map<String, String> config) {
        final String configType = params.getString(GLOBAL_SCAN_CONTROL);
        config.put(GLOBAL_SCAN_CONTROL, configType);

        if (COSTUME_CONFIGURATION_CONTROL.equals(configType)) {
            config.put(THRESHOLDS_ENABLED, params.getString(THRESHOLDS_ENABLED));
            config.put(HIGH_THRESHOLD, params.getString(HIGH_THRESHOLD));
            config.put(MEDIUM_THRESHOLD, params.getString(MEDIUM_THRESHOLD));
            config.put(LOW_THRESHOLD, params.getString(LOW_THRESHOLD));
            config.put(OSA_THRESHOLDS_ENABLED, params.getString(OSA_THRESHOLDS_ENABLED));
            config.put(OSA_HIGH_THRESHOLD, params.getString(OSA_HIGH_THRESHOLD));
            config.put(OSA_MEDIUM_THRESHOLD, params.getString(OSA_MEDIUM_THRESHOLD));
            config.put(OSA_LOW_THRESHOLD, params.getString(OSA_LOW_THRESHOLD));
        }

        return config;
    }

    //the method initialized the CxClient service
    private boolean tryLogin(String userName, String cxPass, String serverUrl) {
        log.debug("tryLogin: server URL: " + serverUrl + " username" + userName);

        if (!StringUtils.isEmpty(serverUrl) && !StringUtils.isEmpty(userName) && !StringUtils.isEmpty(cxPass)) {
            try {
                URL cxUrl = new URL(serverUrl);
                cxClientService = new CxClientServiceImpl(cxUrl, userName, decrypt(cxPass), true);
                cxClientService.checkServerConnectivity();
                cxClientService.loginToServer();
                return true;
            } catch (MalformedURLException e) {
                log.debug("Failed to login to retrieve data from server. " + e.getMessage(), e);
            } catch (CxClientException e) {
                log.debug("Failed to login to retrieve data from server. " + e.getMessage(), e);
            } catch (Exception e) {
                log.debug("Failed to login to retrieve data from server. " + e.getMessage(), e);
            }
        }
        return false;
    }

    private Map<String, String> convertPresetType(List<com.checkmarx.v7.Preset> oldType) {
        HashMap<String, String> newType = new HashMap<String, String>();
        for (com.checkmarx.v7.Preset preset : oldType) {
            newType.put(Long.toString(preset.getID()), preset.getPresetName());
        }

        return sortMap(newType);
    }

    private Map<String, String> convertTeamPathType(ArrayOfGroup oldType) {
        HashMap<String, String> newType = new HashMap<String, String>();
        for (Group group : oldType.getGroup()) {
            newType.put(group.getID(), group.getGroupName());
        }
        return sortMap(newType);
    }

    private Map<String, String> sortMap(HashMap<String, String> unsortedMap) {
        Comparator<String> comparator2 = new ValueComparator<String, String>(unsortedMap);
        TreeMap<String, String> sortedMap = new TreeMap<String, String>(comparator2);
        sortedMap.putAll(unsortedMap);

        return sortedMap;
    }

    private String encrypt(String password) {
        String encPass;
        try {
            encPass = new EncryptionServiceImpl().encrypt(password);
        } catch (EncryptionException e) {
            encPass = "";
        }
        return encPass;
    }

    private String decrypt(String password) {
        String encPass;
        try {
            encPass = new EncryptionServiceImpl().decrypt(password);
        } catch (EncryptionException e) {
            encPass = "";
        }

        return encPass;
    }

    //Validation methods
    @Override
    public void validate(@NotNull final ActionParametersMap params, @NotNull final ErrorCollection errorCollection) {
        super.validate(params, errorCollection);
        String useGlobal = params.getString(GLOBAL_SERVER_CREDENTIALS);
        if (COSTUME_CONFIGURATION_SERVER.equals(useGlobal)) {
            validateNotEmpty(params, errorCollection, USER_NAME);
            validateNotEmpty(params, errorCollection, PASSWORD);
            validateNotEmpty(params, errorCollection, SERVER_URL);
            validateUrl(params, errorCollection, SERVER_URL);
        }

        validateNotEmpty(params, errorCollection, PROJECT_NAME);
        useGlobal = params.getString(GLOBAL_CXSAST);
        if (COSTUME_CONFIGURATION_CXSAST.equals(useGlobal)) {
            validateNotNegative(params, errorCollection, SCAN_TIMEOUT_IN_MINUTES);
        }

        useGlobal = params.getString(GLOBAL_SCAN_CONTROL);
        if (COSTUME_CONFIGURATION_CONTROL.equals(useGlobal)) {
            validateNotNegative(params, errorCollection, HIGH_THRESHOLD);
            validateNotNegative(params, errorCollection, MEDIUM_THRESHOLD);
            validateNotNegative(params, errorCollection, LOW_THRESHOLD);
            validateNotNegative(params, errorCollection, OSA_HIGH_THRESHOLD);
            validateNotNegative(params, errorCollection, OSA_MEDIUM_THRESHOLD);
            validateNotNegative(params, errorCollection, OSA_LOW_THRESHOLD);
        }
    }

    private void validateNotEmpty(@NotNull ActionParametersMap params, @NotNull final ErrorCollection errorCollection, @NotNull String key) {
        final String value = params.getString(key);
        if (StringUtils.isEmpty(value)) {
            errorCollection.addError(key, ((ConfigureBuildTasks) errorCollection).getText(key + ".error"));
        }
    }

    private void validateNotNegative(@NotNull ActionParametersMap params, @NotNull final ErrorCollection errorCollection, @NotNull String key) {
        final String value = params.getString(key);
        if (!StringUtils.isEmpty(value)) {
            try {
                int num = Integer.parseInt(value);
                if (num > 0) {
                    return;
                }
                errorCollection.addError(key, ((ConfigureBuildTasks) errorCollection).getText(key + ".notPositive"));
            } catch (Exception e) {
                errorCollection.addError(key, ((ConfigureBuildTasks) errorCollection).getText(key + ".notPositive"));
            }
        }
    }

    private String getAdminConfig(String key) {
        if (adminConfig == null) {
            adminConfig = (AdministrationConfiguration) ContainerManager.getComponent(ADMINISTRATION_CONFIGURATION);
        }
        return StringUtils.defaultString(adminConfig.getSystemProperty(key));
    }

    private void validateUrl(@NotNull ActionParametersMap params, @NotNull final ErrorCollection errorCollection, @NotNull String key) {
        final String value = params.getString(key);
        if (!StringUtils.isEmpty(value)) {
            try {
                URL url = new URL(value);
                if (url.getPath().length() > 0) {
                    errorCollection.addError(key, ((ConfigureBuildTasks) errorCollection).getText(key + "error.malformed"));
                }
            } catch (MalformedURLException e) {
                errorCollection.addError(key, ((ConfigureBuildTasks) errorCollection).getText(key + "error.malformed"));
            }
        }
    }
}
