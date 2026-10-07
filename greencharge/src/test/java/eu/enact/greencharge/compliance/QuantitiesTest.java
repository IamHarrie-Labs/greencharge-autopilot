package eu.enact.greencharge.compliance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuantitiesTest {

    @Test
    void cpu() {
        assertThat(Quantities.cpuCores("500m")).isEqualTo(0.5);
        assertThat(Quantities.cpuCores("2")).isEqualTo(2.0);
        assertThat(Quantities.cpuString(0.5)).isEqualTo("500m");
        assertThat(Quantities.cpuString(1)).isEqualTo("1");
    }

    @Test
    void memory() {
        assertThat(Quantities.bytes("512Mi")).isEqualTo(512L * 1024 * 1024);
        assertThat(Quantities.bytes("2Gi")).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(Quantities.bytes("1G")).isEqualTo(1_000_000_000L);
        assertThat(Quantities.toBinaryString(2L * 1024 * 1024 * 1024)).isEqualTo("2Gi");
        assertThat(Quantities.toBinaryString(512L * 1024 * 1024)).isEqualTo("512Mi");
    }
}
