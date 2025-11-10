package com.example.medipairing.ui.auth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.util.Log;
import android.content.Intent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.medipairing.R;
import com.example.medipairing.ui.medipairing.MedipairingFragment;
import com.example.medipairing.util.GoogleConfig;
import com.example.medipairing.util.SessionManager;

import com.kakao.sdk.auth.model.OAuthToken;
import com.kakao.sdk.user.UserApiClient;
import com.kakao.sdk.user.model.User;

import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;



public class LoginFragment extends Fragment {
    private static final boolean CALL_LEGACY_GET_LOGIN = false;
    private static final boolean ALWAYS_SHOW_GOOGLE_ACCOUNT_CHOOSER = true; // 개발용: 항상 계정 선택 UI 표시

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_login, container, false);
        setupGoogle();
        bind(v);
        return v;
    }

    private void bind(View v) {
        View kakao = v.findViewById(R.id.btn_login_kakao);
        View google = v.findViewById(R.id.btn_login_google);
        View scanButton = v.findViewById(R.id.tv_scan_button);

        kakao.setOnClickListener(v1 -> loginWithKakao(/*forceReauthForDemo=*/false));
        google.setOnClickListener(v12 -> loginWithGoogle());
        scanButton.setOnClickListener(v13 -> {
            if (getActivity() != null) {
                getParentFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, new MedipairingFragment())
                        .addToBackStack(null)
                        .commit();
            }
        });
    }

    private volatile boolean serverAuthCompleted = false;

    private void loginWithKakao(boolean forceReauthForDemo) {
        // 콜백 공통 처리자
        Function2<OAuthToken, Throwable, Unit> callback = (token, error) -> {
            if (error != null) {
                Log.e("LoginFragment", "Kakao login error", error);
                postToast("로그인 실패: " + error.getMessage());
                return Unit.INSTANCE;
            }
            // 사용자 이메일 조회 및 필요한 경우 추가 동의 요청
            fetchKakaoEmailOrRequestScope();
            return Unit.INSTANCE;
        };

        Runnable startLogin = () -> {
            if (UserApiClient.getInstance().isKakaoTalkLoginAvailable(requireContext())) {
                UserApiClient.getInstance().loginWithKakaoTalk(requireContext(), callback);
            } else {
                UserApiClient.getInstance().loginWithKakaoAccount(requireContext(), callback);
            }
        };

        if (forceReauthForDemo) {
            UserApiClient.getInstance().logout(unused -> { startLogin.run(); return null; });
        } else {
            startLogin.run();
        }
    }
    // Kakao 이메일 조회 및 추가 동의 요청 처리
    private void fetchKakaoEmailOrRequestScope() {
        UserApiClient.getInstance().me((User user, Throwable userError) -> {
            if (userError != null) {
                Log.e("LoginFragment", "Kakao me() error", userError);
                // 사용자 정보 조회 실패해도 서버 검증은 토큰으로 가능
                String accessToken = null;
                com.kakao.sdk.auth.model.OAuthToken tk = com.kakao.sdk.auth.TokenManagerProvider.getInstance().getManager().getToken();
                if (tk != null) accessToken = tk.getAccessToken();
                sendKakaoLoginToApi(accessToken);
                return null;
            }
            // Log and toast Kakao app-scoped user ID
            if (user != null) {
                long appUserId = user.getId();
                // Try to send to local server as requested
                String accessToken = null;
                com.kakao.sdk.auth.model.OAuthToken tk = com.kakao.sdk.auth.TokenManagerProvider.getInstance().getManager().getToken();
                if (tk != null) accessToken = tk.getAccessToken();
                sendKakaoLoginToApi(accessToken);
                // no legacy call
            }
            String email = user.getKakaoAccount() != null ? user.getKakaoAccount().getEmail() : null;
            Boolean needs = user.getKakaoAccount() != null ? user.getKakaoAccount().getEmailNeedsAgreement() : null;

            if (email == null && Boolean.TRUE.equals(needs)) {
                java.util.List<String> scopes = java.util.Collections.singletonList("account_email");
                UserApiClient.getInstance().loginWithNewScopes(requireContext(), scopes, null, (t, e) -> {
                    if (e != null) {
                        Log.e("LoginFragment", "Kakao newScopes error", e);
                        if (getContext() != null) {
                            SessionManager.setProvider(getContext(), "kakao");
                            SessionManager.setEmail(getContext(), null);
                        }
                        onLoginSuccess();
                        return Unit.INSTANCE;
                    }
                    if (t != null) {
                        // Avoid exposing access token in logs or UI
                    }
                    UserApiClient.getInstance().me((user2, e2) -> {
                        if (user2 != null) {
                            long appUserId2 = user2.getId();
                            String accessToken2 = null;
                            com.kakao.sdk.auth.model.OAuthToken tk2 = com.kakao.sdk.auth.TokenManagerProvider.getInstance().getManager().getToken();
                            if (tk2 != null) accessToken2 = tk2.getAccessToken();
                            sendKakaoLoginToApi(accessToken2);
                            // no legacy call
                        }
                        String email2 = (user2 != null && user2.getKakaoAccount() != null)
                                ? user2.getKakaoAccount().getEmail() : null;
                        if (getContext() != null) {
                            SessionManager.setProvider(getContext(), "kakao");
                            SessionManager.setEmail(getContext(), email2);
                        }
                        // 서버 확인 이후에 최종 로그인 처리
                        return null;
                    });
                    return Unit.INSTANCE;
                });
            } else {
                if (getContext() != null) {
                    SessionManager.setProvider(getContext(), "kakao");
                    SessionManager.setEmail(getContext(), email);
                }
                // 서버 확인 이후에 최종 로그인 처리
            }
            return null;
        });
    }

    private GoogleSignInClient googleClient;
    private ActivityResultLauncher<Intent> googleLauncher;
    private void setupGoogle() {
        final String webClientId = GoogleConfig.getDefaultWebClientId(requireContext());
        GoogleConfig.logSigningInfo(requireContext());
        boolean isDebug = false;
        try { isDebug = (requireContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0; } catch (Throwable ignored) {}
        if (isDebug) {
            Log.i("GoogleIdToken", "default_web_client_id=" + String.valueOf(webClientId));
        }

        GoogleSignInOptions.Builder gsoBuilder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail();
        if (webClientId != null) {
            gsoBuilder.requestIdToken(webClientId);
        } else {
            Log.e("GoogleIdToken", "default_web_client_id missing. Check google-services.json & Firebase config");
        }
        GoogleSignInOptions gso = gsoBuilder.build();
        googleClient = GoogleSignIn.getClient(requireContext(), gso);

        googleLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    try {
                        GoogleSignInAccount acct =
                                GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);

                        String email = acct != null ? acct.getEmail() : null;
                        String idToken = acct != null ? acct.getIdToken() : null;
                        if (idToken == null) {
                            Log.w("GoogleIdToken", "idToken is null. Check Web Client ID & SHA-1 and re-download google-services.json");
                            postToast("토큰 없음");
                            return;
                        }
                        if (getContext() != null) {
                            SessionManager.setProvider(getContext(), "google");
                            SessionManager.setEmail(getContext(), email);
                        }
                        // 서버로 로그인 요청
                        sendGoogleLoginToApi(idToken);
                    } catch (ApiException e) {
                        postToast("로그인 실패: " + e.getStatusCode());
                    }
                }
        );
    }
    private void loginWithGoogle() {
        if (googleClient == null || googleLauncher == null) return;

        if (ALWAYS_SHOW_GOOGLE_ACCOUNT_CHOOSER) {
            // 캐시된 기본 계정을 비워 항상 선택 창을 띄움
            googleClient.signOut().addOnCompleteListener(task -> {
                try {
                    googleLauncher.launch(googleClient.getSignInIntent());
                } catch (Exception ignored) {
                    googleLauncher.launch(googleClient.getSignInIntent());
                }
            });
            return;
        }

        // 기본 동작: 이미 로그인되어 있으면 그대로 사용, 아니면 계정 선택
        GoogleSignInAccount last = GoogleSignIn.getLastSignedInAccount(requireContext());
        if (last != null) {
            // 이전 로그인 기록이 있어도 서버 검증을 위해 인텐트 실행하여 최신 idToken 확보
            googleLauncher.launch(googleClient.getSignInIntent());
        } else {
            googleLauncher.launch(googleClient.getSignInIntent());
        }
    }

    // legacy /login 호출 메서드는 제거되었습니다. /auth/kakao/login 만 사용합니다.

    // POST http://localhost:4000/auth/kakao/login with Authorization: Bearer <accessToken>
    // 200: { login:"ok", user_id }
    // 409: { signup_required:true, signup_token }
    private void sendKakaoLoginToApi(String accessToken) {
        if (accessToken == null) return;
        String host = "43.200.178.100";
        if (android.os.Build.FINGERPRINT != null && android.os.Build.FINGERPRINT.contains("generic")) {
            host = "10.0.2.2";
        }
        final String finalHost = host;
        String url = "http://" + finalHost + ":8000/auth/kakao/login";
        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(body)
                .build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                Log.e("LoginFragment", "api login failed", e);
                postToast("서버 통신 실패");
            }
            @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                String res = response.body() != null ? response.body().string() : null;
                Log.i("LoginFragment", "api login resp(" + response.code() + ")");
                new Handler(Looper.getMainLooper()).post(() -> {
                    int code = response.code();
                    JSONObject json;
                    try { json = (res != null && res.trim().startsWith("{")) ? new JSONObject(res) : new JSONObject(); }
                    catch (Exception ignore) { json = new JSONObject(); }

                    if (code == 200 && "ok".equals(json.optString("login"))) {
                        String jwt = json.optString("access_token", null);
                        if (jwt == null || jwt.isEmpty()) jwt = json.optString("token", null);
                        if (jwt != null && !jwt.isEmpty()) com.example.medipairing.util.SessionManager.setJwt(getContext(), jwt);
                        Log.i("LoginFragment", "login: existing user");
                        serverAuthCompleted = true;
                        onLoginSuccess();
                    } else if (code == 409) {
                        String signupToken = json.optString("signup_token", null);
                        Log.i("LoginFragment", "needs signup, token=" + (signupToken != null));
                        if (signupToken != null) {
                            sendKakaoSignupToApi(signupToken, finalHost);
                        } else {
                            postToast("signup_token 없음");
                        }
                    } else {
                        postToast("서버 응답 확인 필요");
                    }
                });
            }
        });
    }

    // POST http://<host>:8000/auth/kakao/signup with JSON body { "signup_token": "..." }
    private void sendKakaoSignupToApi(String signupToken, String host) {
        if (signupToken == null) return;
        String url = "http://" + host + ":8000/auth/kakao/signup";
                Log.i("LoginFragment", "api signup POST(JSON)");
        OkHttpClient client = new OkHttpClient();
        org.json.JSONObject json = new org.json.JSONObject();
        try { json.put("signup_token", signupToken); } catch (Exception ignored) {}
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                Log.e("LoginFragment", "api signup failed", e);
                postToast("가입 실패");
            }
            @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                String res = response.body() != null ? response.body().string() : null;
                Log.i("LoginFragment", "api signup resp(" + response.code() + "): " + res);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (response.isSuccessful()) {
                        try {
                            org.json.JSONObject obj = new org.json.JSONObject(res != null ? res : "{}");
                            String jwt = obj.optString("access_token", null);
                            if (jwt == null || jwt.isEmpty()) jwt = obj.optString("token", null);
                            if (jwt != null && !jwt.isEmpty()) com.example.medipairing.util.SessionManager.setJwt(getContext(), jwt);
                        } catch (Exception ignored) {}
                        postToast("가입 완료");
                        serverAuthCompleted = true;
                        onLoginSuccess();
                    } else {
                        postToast("가입 실패(" + response.code() + ")");
                    }
                });
            }
        });
    }

    private void googleSignOut() { googleClient.signOut(); }
    private void googleRevoke() { googleClient.revokeAccess(); }


    private void onLoginSuccess() {
        if (getContext() != null) {
            SessionManager.setLoggedIn(getContext(), true);
            String provider = SessionManager.getProvider(getContext());
            Log.i("LoginFragment", "Login success provider=" + provider);
            postToast("로그인 성공");
        }
        if (!serverAuthCompleted || !isAdded()) {
            Log.w("LoginFragment", "Fragment not attached; skip navigation");
            return;
        }
        if (getActivity() != null) {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new com.example.medipairing.ui.home.HomeFragment())
                    .commit();
        }
    }

    private void postToast(String msg) {
        final android.content.Context ctx = getContext();
        if (ctx == null) return;
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    // Google 로그인: 서버로 ID 토큰을 보내 로그인 처리
    private void sendGoogleLoginToApi(String idToken) {
        String host = "43.200.178.100";
        if (android.os.Build.FINGERPRINT != null && android.os.Build.FINGERPRINT.contains("generic")) {
            host = "10.0.2.2";
        }
        final String finalHost = host;
        String url = "http://" + finalHost + ":8000/auth/google/login";
        OkHttpClient client = new OkHttpClient();
        org.json.JSONObject json = new org.json.JSONObject();
        try { json.put("id_token", idToken); } catch (Exception ignored) {}
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder().url(url).post(body).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                Log.e("LoginFragment", "google api login failed", e);
                postToast("서버 통신 실패");
            }
            @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                String res = response.body() != null ? response.body().string() : null;
                Log.i("LoginFragment", "google api login resp(" + response.code() + ")");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (response.isSuccessful()) {
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(res != null ? res : "{}");
                            String jwt = json.optString("access_token", null);
                            if (jwt == null || jwt.isEmpty()) jwt = json.optString("token", null);
                            if (jwt != null && !jwt.isEmpty()) com.example.medipairing.util.SessionManager.setJwt(getContext(), jwt);
                        } catch (Exception ignored) {}
                        serverAuthCompleted = true;
                        postToast("로그인 완료");
                        onLoginSuccess();
                    } else {
                        postToast("로그인 실패(" + response.code() + ")");
                    }
                });
            }
        });
    }
}
