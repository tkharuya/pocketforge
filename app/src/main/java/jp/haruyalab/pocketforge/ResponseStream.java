package jp.haruyalab.pocketforge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.TreeMap;

/** SSE framing and Responses terminal-state validation; no Android/UI dependency. */
final class ResponseStream {
    interface Events {
        void delta(String text);
        void status(String text);
        boolean cancelled();
    }

    static final class ApiException extends IOException {
        final String code;
        ApiException(String code, String detail) {
            super(message(code, detail));
            this.code = code;
        }
    }

    static JSONArray read(Reader source, Events events) throws Exception {
        BufferedReader reader = new BufferedReader(source);
        StringBuilder data = new StringBuilder();
        String event = "";
        Map<Integer, JSONObject> completedItems = new TreeMap<>();
        String line;
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            if (events.cancelled()) throw new IOException("処理を停止しました。");
            if (first && line.startsWith("\ufeff")) line = line.substring(1);
            first = false;
            if (line.isEmpty()) {
                if (data.length() != 0) {
                    JSONArray result = frame(event, data.toString(), completedItems, events);
                    if (result != null) return result;
                }
                event = "";
                data.setLength(0);
            } else if (!line.startsWith(":")) {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if ("event".equals(field)) event = value;
                if ("data".equals(field)) {
                    if (data.length() > 0) data.append('\n');
                    data.append(value);
                    if (data.length() > 16 * 1024 * 1024) throw new IOException("受信イベントが大きすぎます。");
                }
            }
        }
        if (events.cancelled()) throw new IOException("処理を停止しました。");
        if (data.length() > 0) {
            JSONArray result = frame(event, data.toString(), completedItems, events);
            if (result != null) return result;
        }
        throw new ApiException("stream_interrupted", "response.completed を受信する前に通信が終了しました。");
    }

    private static JSONArray frame(String event, String data, Map<Integer, JSONObject> items,
                                   Events events) throws Exception {
        if ("[DONE]".equals(data.trim())) return null;
        JSONObject frame = new JSONObject(data);
        String type = frame.optString("type", event);
        if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
            events.delta(frame.optString("delta", ""));
        } else if ("response.output_item.added".equals(type)) {
            JSONObject item = frame.optJSONObject("item");
            if (item != null && "function_call".equals(item.optString("type")))
                events.status("ツール呼び出しを準備: " + item.optString("name"));
        } else if ("response.output_item.done".equals(type)) {
            JSONObject item = frame.optJSONObject("item");
            if (item != null) items.put(frame.optInt("output_index", items.size()), item);
        } else if ("response.failed".equals(type) || "response.incomplete".equals(type)
                || "response.cancelled".equals(type) || "error".equals(type)) {
            JSONObject response = frame.optJSONObject("response");
            JSONObject error = response == null ? frame.optJSONObject("error") : response.optJSONObject("error");
            if (error == null && "error".equals(type)) error = frame;
            JSONObject incomplete = response == null ? null : response.optJSONObject("incomplete_details");
            String code = error == null ? type : error.optString("code", type);
            String detail = error == null ? "" : error.optString("message", "");
            if (incomplete != null) detail = incomplete.optString("reason", detail);
            throw new ApiException(code, detail);
        } else if ("response.completed".equals(type)) {
            JSONObject response = frame.optJSONObject("response");
            if (response == null) throw new ApiException("invalid_response", "完了イベントに response がありません。");
            String status = response.optString("status", "completed");
            if (!"completed".equals(status)) throw new ApiException(status, "応答が完了していません。");
            JSONArray output = response.optJSONArray("output");
            if (output == null) {
                output = new JSONArray();
                for (JSONObject item : items.values()) output.put(item);
            }
            // Item-done carries the complete encrypted reasoning; added items must never be replayed.
            for (int i = 0; i < output.length(); i++) {
                JSONObject done = items.get(i);
                JSONObject current = output.optJSONObject(i);
                if (done != null && current != null && "reasoning".equals(current.optString("type"))
                        && !done.isNull("encrypted_content")) output.put(i, done);
            }
            return output;
        }
        return null;
    }

    static String message(String code, String detail) {
        String prefix;
        if ("subscription_sharing_usage_limit_exceeded".equals(code))
            prefix = "ChatGPTプランの共有利用枠に達しました。利用枠の回復後に再実行してください。";
        else if ("subscription_sharing_usage_unavailable".equals(code))
            prefix = "このアカウントではChatGPTプランの共有利用が現在利用できません。アカウントとプランの設定を確認してください。";
        else if ("context_length_exceeded".equals(code))
            prefix = "会話がモデルの上限を超えました。新しい会話を開始してください。";
        else if ("stream_interrupted".equals(code))
            prefix = "通信が途中で終了しました。完了は確認できていません。";
        else if ("response.incomplete".equals(code))
            prefix = "モデルの応答が未完了で終了しました。";
        else if ("invalid_api_key".equals(code) || "token_expired".equals(code) || "401".equals(code))
            prefix = "認証が失効しました。ChatGPTに再ログインしてください。";
        else if ("429".equals(code) || "rate_limit_exceeded".equals(code))
            prefix = "利用制限に達しました。時間を置いて再実行してください。";
        else prefix = "AIの処理に失敗しました。";
        String cleaned = detail == null ? "" : detail.trim();
        if (cleaned.length() > 800) cleaned = cleaned.substring(0, 800) + "…";
        return prefix + " [" + code + "]" + (cleaned.isEmpty() ? "" : "\n" + cleaned);
    }
}
