package jp.haruyalab.pocketforge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;

/** Offline protocol + real local-file agent-loop tests; no account or network calls. */
public final class PocketAgentTest {
    private static int assertions;
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static JSONObject object(String json) throws Exception { return new JSONObject(json); }
    private static String sse(JSONObject event) { return "data: " + event.toString() + "\n\n"; }
    private static JSONObject call(String id, String name, JSONObject args) throws Exception {
        return new JSONObject().put("type", "function_call").put("namespace", "workspace")
                .put("name", name).put("call_id", id).put("arguments", args.toString());
    }
    private static JSONObject message(String text) throws Exception {
        return new JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text)));
    }
    private static final class Events implements ResponseStream.Events {
        final StringBuilder text = new StringBuilder();
        boolean stopped;
        public void delta(String delta) { text.append(delta); }
        public void status(String status) { }
        public boolean cancelled() { return stopped; }
    }
    private static final class Listener implements AgentEngine.Listener {
        String complete, error;
        int toolCalls;
        public void onDelta(String text) { }
        public void onStatus(String text) { }
        public void onTool(String name, String detail) { toolCalls++; }
        public void onComplete(String text) { complete = text; }
        public void onError(String message) { error = message; }
    }
    private static void fails(String stream, String expectedCode) throws Exception {
        try {
            ResponseStream.read(new StringReader(stream), new Events());
            throw new AssertionError("Expected " + expectedCode);
        } catch (ResponseStream.ApiException e) { check(e.code.equals(expectedCode), "Failure code " + expectedCode); }
    }
    public static void main(String[] args) throws Exception {
        Events events = new Events();
        JSONObject reasoning = object("{\"type\":\"reasoning\",\"id\":\"r1\",\"summary\":[],\"encrypted_content\":\"complete-secret\"}");
        JSONArray responseItems = new JSONArray().put(object("{\"type\":\"reasoning\",\"id\":\"r1\",\"summary\":[]}")).put(message("こんにちは"));
        String stream = "\ufeff: heartbeat\r\n\r\n"
                + "event: response.output_text.delta\r\ndata: {\"delta\":\r\ndata: \"こんにちは\"}\r\n\r\n"
                + sse(new JSONObject().put("type", "response.output_item.done").put("output_index", 0).put("item", reasoning))
                + sse(new JSONObject().put("type", "response.completed").put("response", new JSONObject().put("status", "completed").put("output", responseItems)));
        JSONArray result = ResponseStream.read(new StringReader(stream), events);
        check(events.text.toString().equals("こんにちは"), "Multiline SSE and Unicode delta");
        check(result.getJSONObject(0).getString("encrypted_content").equals("complete-secret"), "Complete encrypted reasoning retained");
        check(AgentEngine.outputText(result).equals("こんにちは"), "Assistant output text extraction");
        fails("data: [DONE]\n\n", "stream_interrupted");
        fails("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n", "stream_interrupted");
        fails("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"limit\"}}}\n\n", "subscription_sharing_usage_limit_exceeded");
        fails("data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n", "response.incomplete");
        Events stopped = new Events(); stopped.stopped = true;
        try { ResponseStream.read(new StringReader(stream), stopped); throw new AssertionError("Cancellation ignored"); }
        catch (IOException expected) { check(expected.getMessage().contains("停止"), "Cancellation recognized"); }

        JSONArray models = AgentEngine.visibleModels(object("{\"models\":[{\"slug\":\"first\",\"display_name\":\"A\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hidden\"},{\"slug\":\"second\",\"visibility\":\"list\"}]}"));
        check(models.length() == 2 && models.getJSONObject(0).getString("slug").equals("first")
                && models.getJSONObject(1).getString("slug").equals("second"), "Catalog filter and server order");
        try { AgentEngine.visibleModels(object("{\"data\":[]}")); throw new AssertionError("Unexpected catalog accepted"); }
        catch (IOException expected) { check(true, "SIWC catalog required"); }
        check(!AgentEngine.createRequest("catalog-model", new JSONArray(), "default").has("reasoning"), "Stored UI default omits reasoning effort");
        check(!AgentEngine.createRequest("catalog-model", new JSONArray(), "auto").has("reasoning"), "Automatic reasoning omitted");

        ProjectStore store = new ProjectStore(Files.createTempDirectory("pocket-agent-tests-").toFile());
        String id = store.createProject("Agent test").getString("id");
        store.writeText(id, "index.html", "before");
        final int[] iteration = {0};
        AgentEngine engine = new AgentEngine(store, (project, code) -> "{\"result\":4,\"logs\":[]}", (request, listener) -> {
            check(!request.getBoolean("store") && request.getBoolean("stream"), "SIWC required flags");
            check(request.has("instructions") && !request.has("previous_response_id"), "Stateless instructions");
            check(request.getJSONArray("tools").getJSONObject(0).getString("type").equals("namespace"), "Namespaced tools");
            JSONArray history = request.getJSONArray("input");
            int turn = iteration[0]++;
            if (turn == 0) {
                return new JSONArray().put(reasoning).put(call("w1", "write_file", new JSONObject().put("path", "index.html").put("text", "<html><body>after</body></html>")));
            }
            if (turn == 1) {
                check(history.getJSONObject(1).getString("encrypted_content").equals("complete-secret"), "Reasoning replayed on follow-up");
                JSONObject toolOutput = history.getJSONObject(history.length() - 1);
                check(toolOutput.getString("type").equals("function_call_output") && toolOutput.getString("call_id").equals("w1"), "Tool output paired by call id");
                check(new JSONObject(toolOutput.getString("output")).getBoolean("ok"), "Actual write succeeded");
                return new JSONArray().put(call("js1", "run_javascript", new JSONObject().put("code", "2 + 2")));
            }
            check(history.getJSONObject(history.length() - 1).getString("call_id").equals("js1"), "JS result returned to model");
            return new JSONArray().put(message("完了"));
        });
        Listener listener = new Listener();
        engine.run(id, "catalog-model", "変更して", "auto", listener);
        check(listener.error == null && "完了".equals(listener.complete), "Agent loop completed");
        check(listener.toolCalls == 2 && iteration[0] == 3, "Write and JS tools actually executed");
        check(store.readText(id, "index.html").contains("after"), "Actual file persisted");
        check(store.history(id).length() == 1, "One pre-mutation snapshot created");
        JSONArray saved = store.loadConversation(id);
        check(saved.length() == 7, "Full conversation persisted");
        check(!engine.isRunning(), "Running flag cleared");

        JSONArray pending = new JSONArray().put(call("pending", "read_file", new JSONObject())).put(call("answered", "list_files", new JSONObject()))
                .put(object("{\"type\":\"function_call_output\",\"call_id\":\"answered\",\"output\":\"ok\"}"));
        AgentEngine.closePendingCalls(pending, "interrupted");
        AgentEngine.closePendingCalls(pending, "interrupted");
        check(pending.length() == 4 && pending.getJSONObject(3).getString("call_id").equals("pending"), "Pending-call recovery is idempotent");

        String failId = store.createProject("Tool failure test").getString("id");
        final int[] failTurn = {0};
        AgentEngine failing = new AgentEngine(store, null, (request, sink) -> {
            if (failTurn[0]++ == 0) return new JSONArray().put(call("bad", "write_file", new JSONObject().put("path", "../escape").put("text", "bad")));
            JSONArray history = request.getJSONArray("input");
            JSONObject toolResult = new JSONObject(history.getJSONObject(history.length() - 1).getString("output"));
            check(!toolResult.getBoolean("ok"), "Traversal rejected and error returned to model");
            return new JSONArray().put(message("修正が必要"));
        });
        Listener failureListener = new Listener();
        failing.run(failId, "catalog-model", "test", "", failureListener);
        check(failureListener.error == null, "Recoverable tool failure does not break loop");

        final int[] jsFailTurn = {0};
        AgentEngine jsFailure = new AgentEngine(store, (project, code) -> "{\"ok\":false,\"error\":\"SyntaxError\"}", (request, sink) -> {
            if (jsFailTurn[0]++ == 0) return new JSONArray().put(call("brokenjs", "run_javascript", new JSONObject().put("code", "const =")));
            JSONArray history = request.getJSONArray("input");
            JSONObject toolResult = new JSONObject(history.getJSONObject(history.length() - 1).getString("output"));
            check(!toolResult.getBoolean("ok"), "JavaScript execution errors are not marked successful");
            return new JSONArray().put(message("JSエラーを確認"));
        });
        jsFailure.run(failId, "catalog-model", "test js", "auto", new Listener());

        AgentEngine bounded = new AgentEngine(store, null, (request, sink) -> new JSONArray().put(call("loop", "list_files", new JSONObject())));
        bounded.setMaxTurns(1);
        Listener boundedListener = new Listener();
        bounded.run(failId, "catalog-model", "loop", null, boundedListener);
        check(boundedListener.complete == null && boundedListener.error.contains("上限"), "Loop limit fails explicitly");
        System.out.println("PocketAgentTest: " + assertions + " assertions passed");
    }
}
