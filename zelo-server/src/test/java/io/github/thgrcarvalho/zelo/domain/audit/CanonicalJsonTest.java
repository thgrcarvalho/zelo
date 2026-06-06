package io.github.thgrcarvalho.zelo.domain.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    @Test
    void sortsObjectKeysAscending() {
        assertThat(CanonicalJson.canonicalize("{\"b\":1,\"a\":2,\"c\":3}"))
                .isEqualTo("{\"a\":2,\"b\":1,\"c\":3}");
    }

    @Test
    void isIndependentOfInputKeyOrder() {
        assertThat(CanonicalJson.canonicalize("{\"b\":1,\"a\":2}"))
                .isEqualTo(CanonicalJson.canonicalize("{\"a\":2,\"b\":1}"));
    }

    @Test
    void normalizesNumberRepresentation() {
        // 1, 1.0 and 1.00 are the same value → same canonical form. This is what
        // makes the hash survive a Postgres jsonb round trip.
        String one = CanonicalJson.canonicalize("{\"n\":1}");
        assertThat(CanonicalJson.canonicalize("{\"n\":1.0}")).isEqualTo(one);
        assertThat(CanonicalJson.canonicalize("{\"n\":1.00}")).isEqualTo(one);
        assertThat(CanonicalJson.canonicalize("{\"n\":1.50}")).isEqualTo("{\"n\":1.5}");
    }

    @Test
    void recursesIntoObjectsAndPreservesArrayOrder() {
        assertThat(CanonicalJson.canonicalize("{\"z\":[3,2,1],\"a\":{\"y\":1,\"x\":2}}"))
                .isEqualTo("{\"a\":{\"x\":2,\"y\":1},\"z\":[3,2,1]}");
    }

    @Test
    void escapesStringsDeterministically() {
        assertThat(CanonicalJson.canonicalize("{\"s\":\"a\\\"b\\nc\\td\"}"))
                .isEqualTo("{\"s\":\"a\\\"b\\nc\\td\"}");
    }

    @Test
    void handlesNullsBooleansAndUnicode() {
        assertThat(CanonicalJson.canonicalize("{\"b\":true,\"n\":null,\"u\":\"caf\\u00e9\"}"))
                .isEqualTo("{\"b\":true,\"n\":null,\"u\":\"café\"}");
    }
}
