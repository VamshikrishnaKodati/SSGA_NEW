package SSGA_Backend.statement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class RealPayloadRenderTest {

    @Test
    void rendersRealSnapshot() throws Exception {
        String json = Files.readString(Path.of(
                System.getenv("TEMP"), "opencode", "real-payload.json"));
        Map<?, ?> payload = JsonMapper.builder().build().readValue(json, Map.class);
        byte[] pdf = StatementPdfRenderer.render(payload);
        Files.write(Path.of("target", "statement-sample-real.pdf"), pdf);
    }
}
