package org.hawkular.alerts.api.services;

import org.hawkular.alerts.api.model.event.Alert;

public interface AlertsHistoryService {

    /**
     * Adds an entry into the PostgreSQL alerts history table for the given alert.
     */
    void put(Alert alert);
}
