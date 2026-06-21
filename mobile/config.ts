// P0 SPIKE config. Fill from operator prerequisite A (native OAuth client IDs) + the api-gateway
// base URL. These are NOT secrets in the OAuth-PKCE sense (public client IDs), but keep real values
// out of git — override via Expo `extra` / env for anything beyond local spiking.

export const config = {
  // Google "iOS"/"Android" OAuth client IDs (Cloud Console). The web client id will NOT work for a
  // native PKCE redirect.
  googleIosClientId: "REPLACE_WITH_GOOGLE_IOS_CLIENT_ID",
  googleAndroidClientId: "REPLACE_WITH_GOOGLE_ANDROID_CLIENT_ID",

  // The api-gateway base URL reachable from the simulator. For an iOS simulator hitting a local
  // gateway this is usually http://localhost:8080; for a physical device use your LAN IP.
  apiBaseUrl: "http://localhost:8080",
};
