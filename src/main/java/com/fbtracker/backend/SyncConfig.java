package com.fbtracker.backend;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class SyncConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String cronExpression;
    private String enabledMetrics;

    public SyncConfig(){
        
    }

    public UUID getId() {
        return id;
    }
    public String getEnabledMetrics() {
        return enabledMetrics;
    }
    public void setEnabledMetrics(String enabledMetrics) {
        this.enabledMetrics = enabledMetrics;
    }
    public String getCronExpression() {
        return cronExpression;
    }
    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }
}
