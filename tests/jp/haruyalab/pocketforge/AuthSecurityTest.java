package jp.haruyalab.pocketforge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** Pure JVM security regression test; Android Keystore and real OAuth require a device. */
public final class AuthSecurityTest {
    private static KeyPair pair;
    private static JSONArray keys;
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    private static void rejects(String label, Checked action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, label + " was accepted");
    }
    private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String token(JSONObject claims, String alg, String kid) throws Exception {
        String signed = b64(new JSONObject().put("alg", alg).put("kid", kid).toString().getBytes(StandardCharsets.UTF_8)) + "." +
                b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate()); signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + b64(signature.sign());
    }
    private static JSONObject claims() throws Exception {
        return new JSONObject().put("iss", "https://auth.openai.com").put("aud", "issued_client")
                .put("sub", "account-subject").put("nonce", "expected-nonce").put("iat", 1000).put("exp", 4600);
    }
    private static JSONObject verify(JSONObject claims) throws Exception {
        return ChatGptAuth.verifyJwt(token(claims,"RS256","test-key"), keys, "issued_client", "expected-nonce", "account-subject", 2000);
    }
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        keys = new JSONArray().put(new JSONObject().put("kid", "test-key").put("kty", "RSA").put("alg", "RS256")
                .put("use", "sig").put("n", b64(publicKey.getModulus().toByteArray())).put("e", b64(publicKey.getPublicExponent().toByteArray())));
        check("account-subject".equals(verify(claims()).getString("sub")), "valid signature");
        rejects("issuer", () -> verify(claims().put("iss", "https://attacker.example")));
        rejects("audience", () -> verify(claims().put("aud", "other-client")));
        rejects("expired token", () -> verify(claims().put("exp", 1000)));
        rejects("future token", () -> verify(claims().put("iat", 4000)));
        rejects("not-before", () -> verify(claims().put("nbf", 4000)));
        rejects("nonce", () -> verify(claims().put("nonce", "wrong")));
        rejects("subject", () -> verify(claims().put("sub", "another-user")));
        rejects("missing subject", () -> verify(claims().put("sub", "")));
        rejects("missing expiration", () -> { JSONObject c = claims(); c.remove("exp"); verify(c); });
        rejects("multiple audience without azp", () -> verify(claims().put("aud", new JSONArray().put("issued_client").put("another"))));
        verify(claims().put("aud", new JSONArray().put("issued_client").put("another")).put("azp", "issued_client")); checks++;
        rejects("wrong azp", () -> verify(claims().put("azp", "another")));
        rejects("algorithm none", () -> ChatGptAuth.verifyJwt(token(claims(),"none","test-key"),keys,"issued_client","expected-nonce",null,2000));
        rejects("unknown key", () -> ChatGptAuth.verifyJwt(token(claims(),"RS256","unknown"),keys,"issued_client","expected-nonce",null,2000));
        String valid = token(claims(), "RS256", "test-key");
        String[] parts = valid.split("\\.");
        String forged = parts[0] + "." + b64(claims().put("sub", "forged").toString().getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        rejects("tampered signature", () -> ChatGptAuth.verifyJwt(forged, keys,"issued_client","expected-nonce",null,2000));
        check("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(ChatGptAuth.pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")), "RFC 7636 PKCE vector");
        check("a+b c".equals(ChatGptAuth.parseQuery("code=a%2Bb+c&state=123").get("code")), "URL decoding");
        rejects("duplicate state", () -> ChatGptAuth.parseQuery("state=first&state=second"));
        rejects("encoded duplicate state", () -> ChatGptAuth.parseQuery("state=first&st%61te=second"));
        rejects("invalid percent", () -> ChatGptAuth.parseQuery("state=%GG"));
        rejects("oversized query", () -> ChatGptAuth.parseQuery("x=" + "x".repeat(17000)));
        System.out.println("AuthSecurityTest: " + checks + " checks passed");
    }
}
