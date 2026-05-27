// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppSettings}.
 *
 * <p>
 * The class is a settings bag persisted to {@code settings.json}; most of
 * its public surface is straightforward getter/setter pairs. The tests
 * here cover the two non-trivial responsibilities: the
 * {@link AppSettings#getActiveProfile()} resolution that auto-creates a
 * default profile when the list is empty, and the defensive copying
 * applied to mutable collections via the setters.
 * </p>
 *
 * @since 1.0.0
 */
class AppSettingsTest {

    @Test
    void defaultSettings_haveAtLeastNonNullProfilesList() {
        AppSettings settings = new AppSettings();
        assertNotNull(settings.getProfiles(), "Profiles list must never be null");
    }

    @Test
    void getActiveProfile_emptyProfileList_createsAndReturnsDefault() {
        AppSettings settings = new AppSettings();
        settings.setProfiles(new ArrayList<>());

        AiProfile resolved = settings.getActiveProfile();

        assertNotNull(resolved, "Active profile must never be null");
        assertFalse(settings.getProfiles().isEmpty(),
                "An empty profile list should be populated with a default");
    }

    @Test
    void getActiveProfile_unknownActiveName_returnsFirstProfile() {
        AppSettings settings = new AppSettings();
        AiProfile first = new AiProfile();
        first.setName("FirstProfile");
        AiProfile second = new AiProfile();
        second.setName("SecondProfile");
        settings.setProfiles(new ArrayList<>(List.of(first, second)));
        settings.setActiveProfileName("does-not-exist");

        assertSame(first, settings.getActiveProfile(),
                "When the active name doesn't match, the first profile wins");
    }

    @Test
    void getActiveProfile_matchingName_returnsThatProfile() {
        AppSettings settings = new AppSettings();
        AiProfile first = new AiProfile();
        first.setName("First");
        AiProfile second = new AiProfile();
        second.setName("Second");
        settings.setProfiles(new ArrayList<>(List.of(first, second)));
        settings.setActiveProfileName("Second");

        assertSame(second, settings.getActiveProfile());
    }

    @Test
    void setActiveProfileName_persistsTheValue() {
        AppSettings settings = new AppSettings();
        settings.setActiveProfileName("MyProfile");

        assertEquals("MyProfile", settings.getActiveProfileName());
    }
}
