// Drives llm_jni.cpp on a desktop JVM with a real GGUF: the same prompt
// rendering, grammar and multi-round tool loop as core/llm/Agent.kt, with
// every tool call answered by a fake success.
import com.sivrad.core.llm.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {
    static LlamaNative n = new LlamaNative();
    static long h;
    static List<String[]> history = new ArrayList<>();
    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static String render(String system) {
        List<String[]> msgs = new ArrayList<>();
        msgs.add(new String[]{"system", system});
        msgs.addAll(history);
        byte[][] r = new byte[msgs.size()][], c = new byte[msgs.size()][];
        for (int i = 0; i < msgs.size(); i++) { r[i] = b(msgs.get(i)[0]); c[i] = b(msgs.get(i)[1]); }
        return new String(n.applyTemplate(h, r, c, true), StandardCharsets.UTF_8);
    }

    // -Dsivrad.nothink=true: hybrid thinking models (Qwen3 0.6B/1.7B), as LlmConfig.noThinking.
    static final boolean NO_THINK = Boolean.getBoolean("sivrad.nothink");

    static String gen(String prompt, String grammar) {
        if (NO_THINK) prompt += "<think>\n\n</think>\n\n";
        StringBuilder out = new StringBuilder();
        long t = System.nanoTime();
        int k = n.generate(h, b(prompt), grammar == null ? null : b(grammar), 256, new float[]{0.7f, 0.8f, 20f, 0f, 42f},
            p -> { out.append(new String(p, StandardCharsets.UTF_8)); return true; });
        float[] st = n.lastStats(h);
        System.err.printf("  (read %d new tokens in %.2fs, wrote %d at %.1f tok/s)%n",
            (int) (st[0] - st[1]), st[2] / 1000, (int) st[3], st[4] > 0 ? st[3] * 1000 / st[4] : 0);
        return out.toString();
    }

    public static void main(String[] a) throws Exception {
        System.load(System.getProperty("sivrad.lib"));
        String system = Files.readString(Path.of(a[1]));
        String grammar = Files.readString(Path.of(a[2]));
        n.backendInit();
        h = n.load(b(a[0]), 4096, 4, 4);
        List<String[]> msgs = new ArrayList<>(); msgs.add(new String[]{"system", system});
        // warm-up (prefill system prompt only)
        long t = System.nanoTime();
        byte[][] r = {b("system")}, c = {b(system)};
        n.generate(h, n.applyTemplate(h, r, c, false), null, 0, new float[]{0.7f,0.8f,20,0,0}, p -> true);
        System.err.printf("warm-up %.1fs%n", (System.nanoTime() - t) / 1e9);
        for (int i = 3; i < a.length; i++) {
            System.out.println("USER: " + a[i]);
            history.add(new String[]{"user", a[i]});
            for (int round = 0; round < 3; round++) {
                String out = gen(render(system), grammar);
                history.add(new String[]{"assistant", out});
                System.out.println("MODEL: " + out.replace("\n", "\\n"));
                if (!out.startsWith("<tool_call>")) break;
                String resp = "{\"ok\": true, \"result\": \"done\"}";
                history.add(new String[]{"user", "<tool_response>\n" + resp + "\n</tool_response>"});
            }
        }
        // cancellation check: abort after 3 pieces
        int[] cnt = {0};
        int k = n.generate(h, b(render(system)), null, 200, new float[]{0.7f,0.8f,20,0,1}, p -> ++cnt[0] < 3);
        System.out.println("stopped after " + k + " tokens");
        n.free(h);
    }
}
