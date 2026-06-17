package com.ashoo.api.dto;

/**
 * Request body for registering a medication.
 *
 * The user supplies their own {@code name} and picks a {@code type} from the fixed
 * dropdown; the controller validates the type against {@link com.ashoo.reminder.MedicationType}.
 *
 * @param name  the user's own medication name (e.g. "Ventolin inhaler")
 * @param type  the medication category (INHALER, ANTIHISTAMINE, …)
 * @param notes optional free-text notes
 */
public record MedicationRequest(String name, String type, String notes) {}
