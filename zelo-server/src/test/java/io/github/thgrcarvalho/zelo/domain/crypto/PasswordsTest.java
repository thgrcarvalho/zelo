package io.github.thgrcarvalho.zelo.domain.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordsTest {

    @Test
    void hashIsSelfDescribingAndNotThePlaintext() {
        String hash = Passwords.hash("correct horse battery staple");
        assertThat(hash).matches("pbkdf2-sha256\\$\\d+\\$[A-Za-z0-9+/]+\\$[A-Za-z0-9+/]+");
        assertThat(hash).doesNotContain("correct horse battery staple");
    }

    @Test
    void verifyAcceptsTheRightPasswordAndRejectsTheWrongOne() {
        String hash = Passwords.hash("s3cret-passw0rd");
        assertThat(Passwords.verify("s3cret-passw0rd", hash)).isTrue();
        assertThat(Passwords.verify("s3cret-passw0rE", hash)).isFalse();
        assertThat(Passwords.verify("", hash)).isFalse();
    }

    @Test
    void eachHashUsesAFreshSaltSoTheSamePasswordHashesDifferently() {
        String a = Passwords.hash("same-password");
        String b = Passwords.hash("same-password");
        assertThat(a).isNotEqualTo(b);
        // ...yet both verify.
        assertThat(Passwords.verify("same-password", a)).isTrue();
        assertThat(Passwords.verify("same-password", b)).isTrue();
    }

    @Test
    void verifyNeverThrowsOnMalformedStoredValues() {
        assertThat(Passwords.verify("x", null)).isFalse();
        assertThat(Passwords.verify(null, "x")).isFalse();
        assertThat(Passwords.verify("x", "not-a-hash")).isFalse();
        assertThat(Passwords.verify("x", "bcrypt$1$2$3")).isFalse();
        assertThat(Passwords.verify("x", "pbkdf2-sha256$notanumber$AA$BB")).isFalse();
    }
}
