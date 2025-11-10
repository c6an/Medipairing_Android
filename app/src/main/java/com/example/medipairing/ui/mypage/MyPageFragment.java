package com.example.medipairing.ui.mypage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medipairing.R;
import com.example.medipairing.model.Medicine;
import com.example.medipairing.ui.auth.LoginFragment;
import com.example.medipairing.ui.medipairing.MedipairingFragment;
import com.example.medipairing.ui.medipairing.MedicineSearchFragment;
import com.example.medipairing.ui.medipairing.MedicineUploadFragment;
import com.example.medipairing.util.SessionManager;

import java.util.ArrayList;
import java.util.List;

public class MyPageFragment extends Fragment {

    private RecyclerView medicineRecyclerView;
    private MedicineAdapter medicineAdapter;
    private List<Medicine> medicineList;
    private View addOptionsOverlay;
    private View navDrawerLayout;
    private View topBar;
    private TextView mypageTitle;
    private boolean isNavDrawerOpen = false;
    // Swipe-to-reveal state
    private int revealedPosition = RecyclerView.NO_POSITION;
    private android.graphics.Rect revealedIconRect = null;
    private boolean deleteInFlight = false;
    private boolean fetchInFlight = false;
    private boolean refetchOnce = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my_page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupViews(view);
        setupRecyclerView(view);
        setupClickListeners(view);
        setupNavDrawer(view);
        // Listen for refresh requests from detail screens
        getParentFragmentManager().setFragmentResultListener("mypage_refresh", this, (requestKey, result) -> fetchMyPills());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Ensure list is fresh when returning from deeper screens
        fetchMyPills();
    }

    private void setupViews(View view) {
        addOptionsOverlay = view.findViewById(R.id.add_options_overlay);
        navDrawerLayout = view.findViewById(R.id.nav_drawer_layout);
        topBar = view.findViewById(R.id.top_bar);
        mypageTitle = view.findViewById(R.id.tv_mypage_title);
    }

    private void setupRecyclerView(View view) {
        medicineRecyclerView = view.findViewById(R.id.recycler_medicine_list);
        medicineRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        medicineList = new ArrayList<>();

        medicineAdapter = new MedicineAdapter(medicineList, medicine -> {
            Fragment f = com.example.medipairing.ui.medipairing.MedicinedeeperFragment.newInstance(
                    medicine.getPillId(),
                    medicine.getUserItemId(),
                    medicine.getUserDefinedName(),
                    medicine.getActualName(),
                    medicine.getImageUrl(),
                    medicine.getIdCode(),
                    medicine.getNote(),
                    medicine.getCategory(),
                    medicine.getEfficacy(),
                    medicine.getPrecautions(),
                    medicine.getStorage()
            );
            navigateTo(f);
        });
        medicineRecyclerView.setAdapter(medicineAdapter);

        fetchMyPills();
        new ItemTouchHelper(new SwipeCallback()).attachToRecyclerView(medicineRecyclerView);

        // Unified touch handler: close nav drawer or handle trash icon tap
        medicineRecyclerView.setOnTouchListener((v, event) -> {
            // If drawer open, close and consume
            if (isNavDrawerOpen) {
                toggleNavDrawer();
                return true;
            }

            // Handle trash icon tap when revealed
            if (revealedPosition != RecyclerView.NO_POSITION && revealedIconRect != null) {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    int x = (int) event.getX();
                    int y = (int) event.getY();
                    RecyclerView.ViewHolder vh = medicineRecyclerView.findViewHolderForAdapterPosition(revealedPosition);
                    if (vh != null) {
                        View item = vh.itemView;
                        int itemLeft = item.getLeft();
                        int itemTop = item.getTop();
                        // Convert touch to item-local coords
                        int localX = x - itemLeft;
                        int localY = y - itemTop;
                        if (revealedIconRect.contains(localX, localY)) {
                            int pos = revealedPosition;
                            triggerDeleteAtPosition(pos, vh);
                            return true;
                        }
                    } else {
                        // Off-screen safety reset
                        revealedPosition = RecyclerView.NO_POSITION;
                        revealedIconRect = null;
                    }
                }
            }
            return false;
        });
    }

    private void triggerDeleteAtPosition(int position, RecyclerView.ViewHolder vh) {
        if (position < 0 || position >= medicineList.size()) return;
        if (deleteInFlight) return;
        deleteInFlight = true;

        Medicine m = medicineList.get(position);
        // Server API expects pill_id in path for delete
        int targetPillId = m.getPillId();
        String url = "http://43.200.178.100:8000/me/pills/" + targetPillId;

        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) {
            if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show());
            deleteInFlight = false;
            return;
        }
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url).delete().header("Authorization", "Bearer "+jwt);

        client.newCall(rb.build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    deleteInFlight = false;
                    android.widget.Toast.makeText(getContext(), "삭제 실패", android.widget.Toast.LENGTH_SHORT).show();
                    // Keep row open for retry
                    if (vh != null) {
                        float density = getResources().getDisplayMetrics().density;
                        int maxRevealPx = (int) (72f * density);
                        vh.itemView.setTranslationX(-maxRevealPx);
                    }
                });
            }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    deleteInFlight = false;
                    if (response.isSuccessful()) {
                        // Close reveal and remove locally
                        closeReveal(vh, true);
                        medicineAdapter.removeItem(position);
                    } else {
                        android.widget.Toast.makeText(getContext(), "삭제 실패("+response.code()+")", android.widget.Toast.LENGTH_SHORT).show();
                        if (vh != null) {
                            float density = getResources().getDisplayMetrics().density;
                            int maxRevealPx = (int) (72f * density);
                            vh.itemView.setTranslationX(-maxRevealPx);
                        }
                    }
                });
            }
        });
    }

    private void closeReveal(RecyclerView.ViewHolder vh, boolean immediate) {
        if (vh == null) return;
        View item = vh.itemView;
        if (immediate) {
            item.setTranslationX(0f);
        } else {
            item.animate().translationX(0f).setDuration(150).start();
        }
        revealedPosition = RecyclerView.NO_POSITION;
        revealedIconRect = null;
    }

    private void initMyPills() {
        org.json.JSONObject json = new org.json.JSONObject();
        okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url("http://43.200.178.100:8000/me/pills")
                .delete(body);
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show()); return; }
        rb.header("Authorization", "Bearer " + jwt);
        okhttp3.Request req = rb.build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "초기화 실패", android.widget.Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    android.widget.Toast.makeText(getContext(), response.isSuccessful() ? "초기화 되었습니다" : "추가 실패(" + response.code() + ")", android.widget.Toast.LENGTH_SHORT).show();
                    medicineList.clear();
                    medicineAdapter.notifyDataSetChanged();
                });
            }
        });
    }
    private void fetchMyPills() {
        if (fetchInFlight) { refetchOnce = true; return; }
        fetchInFlight = true;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url("http://43.200.178.100:8000/me/pills");
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { fetchInFlight=false; if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show()); return; }
        rb.header("Authorization", "Bearer " + jwt);
        okhttp3.Request req = rb.build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                fetchInFlight = false;
                if (refetchOnce) { refetchOnce = false; fetchMyPills(); }
            }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body() == null) { fetchInFlight=false; if (refetchOnce){refetchOnce=false; fetchMyPills();} return; }
                String body = response.body().string();
                java.util.ArrayList<Medicine> list = new java.util.ArrayList<>();
                org.json.JSONArray arr = null;
                try { arr = new org.json.JSONArray(body); } catch (Exception ignored) {}
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        String alias = it.optString("alias", null);
                        String note = it.optString("note", null);
                        String efficacy = it.optString("efficacy", null);
                        String precautions = it.optString("precautions", null);
                        String storage = it.optString("storage", null);
                        org.json.JSONObject p = it.optJSONObject("pill");
                        if (p == null) continue;
                        String name = p.optString("name", "");
                        String category = p.optString("category", null);
                        String imageUrl = p.optString("image_url", null);
                        int pillId = p.optInt("id", -1);
                        int imgRes = R.mipmap.ic_launcher;
                        list.add(new Medicine(alias != null?alias:"", name, "", imgRes, pillId, imageUrl, note, -1, category, efficacy, precautions, storage));
                    }
                }
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    medicineList.clear();
                    if (com.example.medipairing.util.SessionManager.isLoggedIn(getContext())) {
                        medicineList.addAll(list);
                    }
                    medicineAdapter.notifyDataSetChanged();
                });
                fetchInFlight = false;
                if (refetchOnce) { refetchOnce = false; fetchMyPills(); }
            }
        });
    }

    private void user_delete() {
        org.json.JSONObject json = new org.json.JSONObject();
        okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url("http://43.200.178.100:8000/user/delete")
                .delete(body);
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) { if (getActivity()!=null) getActivity().runOnUiThread(() -> android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show()); return; }
        rb.header("Authorization", "Bearer " + jwt);
        okhttp3.Request req = rb.build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
           @Override public void onFailure(okhttp3.Call call, java.io.IOException e) { }
           @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
               if (!response.isSuccessful() || response.body() == null) return;
               if (getContext() != null) {
                   SessionManager.clearUser(getContext());
               }
           }
        });
    }
    private void setupClickListeners(View view) {
        view.findViewById(R.id.btn_return).setOnClickListener(v -> {
            // Always navigate to HomeFragment
            getParentFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new com.example.medipairing.ui.home.HomeFragment())
                    .commit();
        });
        view.findViewById(R.id.btn_add_medicine).setOnClickListener(v -> toggleAddOptionsOverlay(true));

        // Overlay buttons
        view.findViewById(R.id.btn_scan_pill_overlay).setOnClickListener(v -> navigateTo(new MedipairingFragment()));
        view.findViewById(R.id.btn_search_medicine_overlay).setOnClickListener(v -> navigateTo(new MedicineSearchFragment()));
        view.findViewById(R.id.btn_upload_medicine_overlay).setOnClickListener(v -> navigateTo(new MedicineUploadFragment()));
        view.findViewById(R.id.btn_cancel_overlay).setOnClickListener(v -> toggleAddOptionsOverlay(false));
        // Recycler touch handled in setupRecyclerView (unified listener)
    }

    private void setupNavDrawer(View view) {
        view.findViewById(R.id.btn_menu).setOnClickListener(v -> toggleNavDrawer());

        view.findViewById(R.id.btn_reset_list).setOnClickListener(v -> {
            new AlertDialog.Builder(getContext())
                    .setTitle("초기화")
                    .setMessage("약 목록을 모두 삭제하시겠습니까?")
                    .setPositiveButton("삭제", (dialog, which) -> {
                        medicineList.clear();
                        medicineAdapter.notifyDataSetChanged();
                        initMyPills();
                        Toast.makeText(getContext(), "목록이 초기화되었습니다.", Toast.LENGTH_SHORT).show();
                        toggleNavDrawer();
                    })
                    .setNegativeButton("취소", null).show();
        });

        view.findViewById(R.id.btn_contact_us).setOnClickListener(v -> {
            Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "ych536595@gmail.com", null));
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Medi-Pairing 문의하기");
            startActivity(Intent.createChooser(emailIntent, "이메일 보내기..."));
            toggleNavDrawer();
        });

        view.findViewById(R.id.btn_delete_account).setOnClickListener(v -> {
            new AlertDialog.Builder(getContext())
                    .setTitle("계정 탈퇴")
                    .setMessage("정말로 계정을 탈퇴하시겠습니까? 모든 데이터가 삭제됩니다.")
                    .setPositiveButton("탈퇴", (dialog, which) -> {
                        user_delete();
                        SessionManager.clearUser(getContext());
                        getParentFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                        getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new LoginFragment()).commit();
                    })
                    .setNegativeButton("취소", null).show();
        });
    }

    private void toggleNavDrawer() {
        isNavDrawerOpen = !isNavDrawerOpen;
        navDrawerLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float targetTranslationY = isNavDrawerOpen ? 0 : -navDrawerLayout.getMeasuredHeight();

        ObjectAnimator animator = ObjectAnimator.ofFloat(navDrawerLayout, "translationY", navDrawerLayout.getTranslationY(), targetTranslationY);
        animator.setDuration(300);

        if (isNavDrawerOpen) {
            navDrawerLayout.setVisibility(View.VISIBLE);
            topBar.setBackgroundColor(Color.parseColor("#1778F2"));
            mypageTitle.setTextColor(Color.WHITE);
        } else {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    navDrawerLayout.setVisibility(View.GONE);
                }
            });
            topBar.setBackgroundColor(Color.WHITE);
            mypageTitle.setTextColor(Color.parseColor("#2c2c2c"));
        }
        animator.start();
    }

    private void toggleAddOptionsOverlay(boolean show) {
        addOptionsOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        medicineRecyclerView.setAlpha(show ? 0.3f : 1.0f);
    }

    private void navigateTo(Fragment fragment) {
        getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, fragment).addToBackStack(null).commit();
        toggleAddOptionsOverlay(false);
    }

    private class SwipeCallback extends ItemTouchHelper.SimpleCallback {

        private final Drawable deleteIcon;
        private final ColorDrawable background;
        private final int maxRevealPx;     // 최대 스와이프 노출 폭
        private final int iconSizePx;      // 아이콘 고정 크기
        private final int iconPaddingPx;   // 아이콘과 우측 여백
        private final int chipPaddingPx;   // 칩 내부 여백
        private final int chipCornerRadiusPx; // 칩 라운드 반경
        private final int minLeftGapPx;    // 경계(왼쪽 박스)와의 최소 간격
        private final int minRightGapPx;   // 우측 끝과의 최소 간격
        private final android.graphics.Paint chipPaint;

        SwipeCallback() {
            super(0, ItemTouchHelper.LEFT);
            deleteIcon = ContextCompat.getDrawable(getContext(), R.drawable.delete);
            background = new ColorDrawable(ContextCompat.getColor(getContext(), R.color.delete_bg));

            float density = getResources().getDisplayMetrics().density;

            this.iconSizePx = (int) (24f * density);
            this.iconPaddingPx = (int) (16f * density);
            this.maxRevealPx = (int) (96f * density);

            this.chipPaddingPx = (int) (8f * density);
            this.chipCornerRadiusPx = (int) (12f * density);
            this.minLeftGapPx = (int) (8f * density);
            this.minRightGapPx = (int) (8f * density);

            chipPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            chipPaint.setColor(ContextCompat.getColor(getContext(), R.color.delete_bg));
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            // View의 실제 위치 기준으로 열림/닫힘 판단
            boolean actuallyRevealed = viewHolder.itemView.getTranslationX() <= -maxRevealPx * 0.9f;
            if (actuallyRevealed) {
                // 오른쪽 스와이프만 허용(닫기)
                return ItemTouchHelper.RIGHT;
            } else {
                // 왼쪽 스와이프만 허용(열기)
                return ItemTouchHelper.LEFT;
            }
        }

        @Override
        public float getSwipeEscapeVelocity(float defaultValue) {
            return Float.MAX_VALUE;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            // Reset translation and state
            if (viewHolder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                viewHolder.itemView.setTranslationX(0f);
            }
            medicineAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
        }

        @Override
        public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
            float width = viewHolder.itemView.getWidth();
            return Math.min(1f, (float) maxRevealPx / Math.max(1f, width));
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            View itemView = viewHolder.itemView;

            if (isCurrentlyActive && revealedPosition != RecyclerView.NO_POSITION && revealedPosition != viewHolder.getAdapterPosition()) {
                RecyclerView.ViewHolder prev = recyclerView.findViewHolderForAdapterPosition(revealedPosition);
                if (prev != null) closeReveal(prev, false);
            }

            float desiredTx;
            boolean isRevealedItem = (viewHolder.getAdapterPosition() == revealedPosition);

            if (isRevealedItem) {
                if (dX > 0) {
                    desiredTx = Math.min(0f, -maxRevealPx + dX);
                } else {
                    desiredTx = -maxRevealPx;
                }
            } else {
                if (dX < 0) {
                    desiredTx = Math.max(dX, -maxRevealPx);
                } else {
                    desiredTx = 0f;
                }
            }

            if (!isCurrentlyActive) {

                if (isRevealedItem) {

                    float settle = (desiredTx > -maxRevealPx * 0.6f) ? 0f : -maxRevealPx;
                    itemView.animate().cancel();
                    itemView.setTranslationX(settle);
                    if (settle == 0f) {
                        revealedPosition = RecyclerView.NO_POSITION;
                        revealedIconRect = null;
                    }
                } else {
                    if (desiredTx < 0) {
                        final int pos = viewHolder.getAdapterPosition();
                        recyclerView.post(() -> {
                            viewHolder.itemView.setTranslationX(-maxRevealPx);
                            revealedPosition = pos;
                        });
                    } else {
                        itemView.animate().cancel();
                        itemView.setTranslationX(0f);
                    }
                }
            } else {
                itemView.setTranslationX(desiredTx);
            }

            float drawTX = itemView.getTranslationX();
            if (drawTX < 0) {
                int right = itemView.getRight();

                int revealLeft = right - maxRevealPx;
                int revealRight = right;


                int iconW = iconSizePx;
                int iconH = iconSizePx;
                if (deleteIcon != null) {
                    int iw = deleteIcon.getIntrinsicWidth();
                    int ih = deleteIcon.getIntrinsicHeight();
                    if (iw > 0 && ih > 0) {
                        float scale = Math.min((float) iconSizePx / iw, (float) iconSizePx / ih);
                        iconW = Math.max(1, Math.round(iw * scale));
                        iconH = Math.max(1, Math.round(ih * scale));
                    }
                }

                int chipLeft = revealLeft + minLeftGapPx;
                int chipTop = itemView.getTop();
                int chipRight = revealRight - minRightGapPx;
                int chipBottom = itemView.getBottom();

                android.graphics.RectF chipRect = new android.graphics.RectF(chipLeft, chipTop, chipRight, chipBottom);
                c.drawRoundRect(chipRect, chipCornerRadiusPx, chipCornerRadiusPx, chipPaint);

                int iconLeft = (int) (chipRect.centerX() - iconW / 2f);
                int iconTop = (int) (chipRect.centerY() - iconH / 2f);
                int iconRight = iconLeft + iconW;
                int iconBottom = iconTop + iconH;

                if (deleteIcon != null) {
                    deleteIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                    deleteIcon.draw(c);
                }

                if (viewHolder.getAdapterPosition() == revealedPosition) {
                    if (revealedIconRect == null) revealedIconRect = new android.graphics.Rect();
                    revealedIconRect.set(
                            (int) chipRect.left - itemView.getLeft(),
                            (int) chipRect.top - itemView.getTop(),
                            (int) chipRect.right - itemView.getLeft(),
                            (int) chipRect.bottom - itemView.getTop()
                    );
                }
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            recyclerView.post(() -> {
                boolean actuallyRevealed = (viewHolder.getAdapterPosition() == revealedPosition);
                viewHolder.itemView.setTranslationX(actuallyRevealed ? -maxRevealPx : 0f);
            });
        }
    }
}
