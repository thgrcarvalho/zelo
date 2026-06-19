package io.github.thgrcarvalho.zelo.infrastructure.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailLinksTest {

    @Test
    void buildsFragmentLinksFromTheConfiguredBase() {
        EmailLinks links = new EmailLinks("https://zelocompliance.com");
        assertThat(links.verifyUrl("tok123")).isEqualTo("https://zelocompliance.com/app/#verify=tok123");
        assertThat(links.resetUrl("tok456")).isEqualTo("https://zelocompliance.com/app/#reset=tok456");
    }

    @Test
    void normalizesATrailingSlashAndHostCaseToTheSameCanonicalLink() {
        EmailLinks withSlash = new EmailLinks("https://Zelocompliance.com/");
        EmailLinks without = new EmailLinks("https://zelocompliance.com");
        assertThat(withSlash.verifyUrl("t")).isEqualTo(without.verifyUrl("t"));
        assertThat(withSlash.verifyUrl("t")).isEqualTo("https://zelocompliance.com/app/#verify=t");
    }

    @Test
    void keepsAnExplicitPort() {
        assertThat(new EmailLinks("https://localhost:8443").resetUrl("t"))
                .isEqualTo("https://localhost:8443/app/#reset=t");
    }

    @Test
    void rejectsNonHttpsOrPathBearingBaseUrls() {
        assertThatThrownBy(() -> new EmailLinks("http://zelocompliance.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailLinks("https://zelocompliance.com/app"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailLinks("https://zelocompliance.com/?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailLinks("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnconfiguredBaseThrowsOnlyWhenAskedToBuildALink() {
        EmailLinks blank = new EmailLinks("   ");
        assertThatThrownBy(() -> blank.verifyUrl("t")).isInstanceOf(IllegalStateException.class);
    }
}
