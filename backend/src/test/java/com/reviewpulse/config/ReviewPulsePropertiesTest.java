package com.reviewpulse.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewPulsePropertiesTest {

    @Test
    void adminTokenIsEnabledOnlyWhenNonBlankAndNeverPrinted() {
        assertThat(new ReviewPulseProperties.Admin(null).enabled()).isFalse();
        assertThat(new ReviewPulseProperties.Admin("   ").enabled()).isFalse();
        ReviewPulseProperties.Admin set = new ReviewPulseProperties.Admin("super-secret-value");
        assertThat(set.enabled()).isTrue();
        assertThat(set.toString()).contains("<set>").doesNotContain("super-secret-value");
        assertThat(new ReviewPulseProperties.Admin("").toString()).contains("<empty>");
    }
}
