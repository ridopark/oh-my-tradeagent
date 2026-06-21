// P0 SPIKE — single sign-in screen. Google OAuth Authorization Code + PKCE via expo-auth-session;
// on success, hand the IdP id_token to the backend exchange and render the resolved tenant (or the
// denial). Facebook is the symmetric second provider, added in P1.
import { useEffect, useState } from "react";
import { Button, SafeAreaView, StyleSheet, Text, View } from "react-native";
import * as Google from "expo-auth-session/providers/google";
import * as WebBrowser from "expo-web-browser";
import { config } from "./config";
import { exchange, type ExchangeResult } from "./lib/auth";

WebBrowser.maybeCompleteAuthSession();

export default function App() {
  const [result, setResult] = useState<ExchangeResult | null>(null);
  const [busy, setBusy] = useState(false);

  // expo-auth-session uses PKCE by default for the auth-code flow and returns an id_token.
  const [request, response, promptAsync] = Google.useAuthRequest({
    iosClientId: config.googleIosClientId,
    androidClientId: config.googleAndroidClientId,
  });

  useEffect(() => {
    const idToken = response?.type === "success" ? response.authentication?.idToken : undefined;
    if (!idToken) return;
    setBusy(true);
    exchange("google", idToken)
      .then(setResult)
      .finally(() => setBusy(false));
  }, [response]);

  return (
    <SafeAreaView style={styles.screen}>
      <View style={styles.card}>
        <Text style={styles.h1}>oh-my-tradeagent — P0 spike</Text>
        <Text style={styles.sub}>native PKCE → tenant</Text>
        <Button
          title={busy ? "Signing in…" : "Sign in with Google"}
          disabled={!request || busy}
          onPress={() => promptAsync()}
        />
        {result?.ok && (
          <Text style={styles.ok}>
            ✅ tenant: {result.tenant}{"\n"}tenantIds: {result.tenantIds.join(", ")}
          </Text>
        )}
        {result && !result.ok && (
          <Text style={styles.deny}>⛔ {result.status} — {result.reason}</Text>
        )}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: "#0f172a", justifyContent: "center", padding: 24 },
  card: { backgroundColor: "#1e293b", borderRadius: 12, padding: 24, gap: 16 },
  h1: { color: "#f1f5f9", fontSize: 18, fontWeight: "600" },
  sub: { color: "#94a3b8", fontSize: 13, marginTop: -8 },
  ok: { color: "#4ade80", fontSize: 14 },
  deny: { color: "#f87171", fontSize: 14 },
});
