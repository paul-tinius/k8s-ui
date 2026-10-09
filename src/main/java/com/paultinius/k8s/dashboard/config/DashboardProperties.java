package com.paultinius.k8s.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties {

    private final Ldap ldap = new Ldap();
    private final Cluster cluster = new Cluster();
    private final Ai ai = new Ai();
    private final Live live = new Live();

    public Ldap getLdap() {
        return ldap;
    }

    public Cluster getCluster() {
        return cluster;
    }

    public Ai getAi() {
        return ai;
    }

    public Live getLive() {
        return live;
    }

    public static class Ldap {
        /**
         * False (the default) disables LDAP entirely so the dashboard runs with
         * no login, the same no-auth experience it had before this feature -
         * used for local development and the demo cluster.
         */
        private boolean enabled = false;
        private String host = "";
        private int port = 3269;
        private String defaultDomain = "";
        private String searchBaseDn = "";
        /** AD group CN. Only its members (via memberOf) may log in. */
        private String group = "";
        private String bindUsername = "";
        private String bindPassword = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDefaultDomain() {
            return defaultDomain;
        }

        public void setDefaultDomain(String defaultDomain) {
            this.defaultDomain = defaultDomain;
        }

        public String getSearchBaseDn() {
            return searchBaseDn;
        }

        public void setSearchBaseDn(String searchBaseDn) {
            this.searchBaseDn = searchBaseDn;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getBindUsername() {
            return bindUsername;
        }

        public void setBindUsername(String bindUsername) {
            this.bindUsername = bindUsername;
        }

        public String getBindPassword() {
            return bindPassword;
        }

        public void setBindPassword(String bindPassword) {
            this.bindPassword = bindPassword;
        }
    }

    public static class Cluster {
        private boolean demoEnabled = true;
        private boolean loadDefaultKubeconfig = true;
        private boolean inCluster;
        private String dataDir = "";
        private List<String> kubeconfigs = new ArrayList<>();

        public boolean isDemoEnabled() {
            return demoEnabled;
        }

        public void setDemoEnabled(boolean demoEnabled) {
            this.demoEnabled = demoEnabled;
        }

        public boolean isLoadDefaultKubeconfig() {
            return loadDefaultKubeconfig;
        }

        public void setLoadDefaultKubeconfig(boolean loadDefaultKubeconfig) {
            this.loadDefaultKubeconfig = loadDefaultKubeconfig;
        }

        public boolean isInCluster() {
            return inCluster;
        }

        public void setInCluster(boolean inCluster) {
            this.inCluster = inCluster;
        }

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        public List<String> getKubeconfigs() {
            return kubeconfigs;
        }

        public void setKubeconfigs(List<String> kubeconfigs) {
            this.kubeconfigs = kubeconfigs == null ? new ArrayList<>() : kubeconfigs;
        }
    }

    public static class Ai {
        private String provider = "xAI";
        private String baseUrl = "https://api.x.ai/v1";
        private String model = "grok-4.7";
        private String apiKey = "";
        private Duration timeout = Duration.ofSeconds(90);

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Live {
        private Duration resourceInterval = Duration.ofSeconds(3);
        private Duration logInterval = Duration.ofSeconds(2);

        public Duration getResourceInterval() {
            return resourceInterval;
        }

        public void setResourceInterval(Duration resourceInterval) {
            this.resourceInterval = resourceInterval;
        }

        public Duration getLogInterval() {
            return logInterval;
        }

        public void setLogInterval(Duration logInterval) {
            this.logInterval = logInterval;
        }
    }
}
