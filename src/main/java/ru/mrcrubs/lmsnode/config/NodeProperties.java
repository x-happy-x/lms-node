package ru.mrcrubs.lmsnode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "node")
public class NodeProperties {
    private String downloadDir = "/downloads";
    private int maxParallel = 1;
    /**
     * JSON file with job state; empty keeps jobs in memory only.
     */
    private String stateFile = "";
    /**
     * Continue jobs that were queued or running when the node stopped.
     */
    private boolean resumeOnStartup = true;

    public String getDownloadDir() {
        return downloadDir;
    }

    public void setDownloadDir(String downloadDir) {
        this.downloadDir = downloadDir;
    }

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile;
    }

    public boolean isResumeOnStartup() {
        return resumeOnStartup;
    }

    public void setResumeOnStartup(boolean resumeOnStartup) {
        this.resumeOnStartup = resumeOnStartup;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }
}
