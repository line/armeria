package example.armeria.xds;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

record ProfileState(String baseProfile, Map<String, String> overrides) {

    ProfileState {
        if (overrides == null) {
            overrides = Map.of();
        }
    }

    ProfileState(String baseProfile) {
        this(baseProfile, Map.of());
    }

    ProfileState mergeFrom(ProfileState update) {
        if (update.baseProfile() != null) {
            return new ProfileState(update.baseProfile(), update.overrides());
        }
        final Map<String, String> merged = new HashMap<>(overrides);
        merged.putAll(update.overrides());
        return new ProfileState(baseProfile, merged);
    }

    boolean shouldUpdate(String resource) {
        return !"override".equals(profileFor(resource));
    }

    String profileFor(String resource) {
        final String override = overrides.get(resource);
        if (override != null) {
            return override;
        }
        final List<String> valid = XdsTemplateReader.VALID_PROFILES.get(resource);
        return valid != null && valid.contains(baseProfile) ? baseProfile : "basic";
    }
}
