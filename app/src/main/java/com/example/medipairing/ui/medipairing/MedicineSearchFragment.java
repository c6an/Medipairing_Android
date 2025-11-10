package com.example.medipairing.ui.medipairing;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medipairing.R;

import java.util.ArrayList;
import java.util.List;

public class MedicineSearchFragment extends Fragment {

    private EditText etSearchQuery;
    private RecyclerView recyclerSearchResults;
    private SearchResultAdapter adapter;
    private final List<PillResult> searchResults = new ArrayList<>();
    private View emptyView;
    private View progressView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_medicine_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etSearchQuery = view.findViewById(R.id.et_search_query);
        recyclerSearchResults = view.findViewById(R.id.recycler_search_results);
        ImageView btnReturn = view.findViewById(R.id.btn_return_search);
        ImageView btnSearch = view.findViewById(R.id.btn_search_icon);
        emptyView = view.findViewById(R.id.tv_empty); // optional
        progressView = view.findViewById(R.id.progress_bar); // optional

        btnReturn.setOnClickListener(v -> getParentFragmentManager().popBackStack());
        btnSearch.setOnClickListener(v -> performSearch());

        setupRecyclerView();

        etSearchQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });
    }

    private void performSearch() {
        String query = etSearchQuery.getText() != null ? etSearchQuery.getText().toString().trim() : "";
        if (query.isEmpty()) {
            android.widget.Toast.makeText(getContext(), "검색어를 입력하세요", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        doSearchRequest(query);
    }

    private void setupRecyclerView() {
        adapter = new SearchResultAdapter(searchResults, pill -> {
            String name = pill.name != null ? pill.name : "";
            String imageUrl = pill.imageUrl;
            String code = pill.imprint != null && !pill.imprint.isEmpty() ? pill.imprint : (pill.externalCode != null ? pill.externalCode : "");
            int pillId = pill.id;
            Fragment info = MedicineinfoFragment.newInstance(name, imageUrl, code, pillId, "", "", "");
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, info)
                    .addToBackStack(null)
                    .commit();
        });
        recyclerSearchResults.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerSearchResults.setAdapter(adapter);
    }

    private void doSearchRequest(String q) {
        showLoading(true);
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        // Avoid '/?q=' redirect; use '/search/pills?q=' directly
        String url = "http://43.200.178.100:8000/search/pills?q=" + safeEncode(q);
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url);
        String jwt = com.example.medipairing.util.SessionManager.getJwt(getContext());
        if (jwt == null || jwt.isEmpty()) {
            // JWT가 없으면 로그인 요구
            showLoading(false);
            android.widget.Toast.makeText(getContext(), "로그인이 필요합니다", android.widget.Toast.LENGTH_SHORT).show();
            return;
        } else {
            rb.header("Authorization", "Bearer " + jwt);
        }
        okhttp3.Request req = rb.build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (getActivity()!=null) getActivity().runOnUiThread(() -> {
                    showLoading(false);
                    android.widget.Toast.makeText(getContext(), "검색 실패", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (getActivity()==null) return;
                String body = response.body() != null ? response.body().string() : null;
                getActivity().runOnUiThread(() -> {
                    showLoading(false);
                    if (!response.isSuccessful() || body == null) {
                        android.widget.Toast.makeText(getContext(), "검색 실패("+response.code()+")", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    java.util.List<PillResult> items = parsePillList(body);
                    searchResults.clear();
                    searchResults.addAll(items);
                    adapter.notifyDataSetChanged();
                    recyclerSearchResults.setVisibility(View.VISIBLE);
                    if (emptyView != null) emptyView.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);
                });
            }
        });
    }

    private static List<PillResult> parsePillList(String body) {
        ArrayList<PillResult> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                PillResult p = new PillResult();
                p.id = o.optInt("id", -1);
                p.externalCode = o.optString("external_code", null);
                p.name = o.optString("name", null);
                p.brand = o.optString("brand", null);
                p.category = o.optString("category", null);
                p.form = o.optString("form", null);
                p.shape = o.optString("shape", null);
                p.color = o.optString("color", null);
                p.imprint = o.optString("imprint", null);
                p.imageUrl = o.optString("image_url", null);
                out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String safeEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private void showLoading(boolean show) {
        if (progressView != null) progressView.setVisibility(show?View.VISIBLE:View.GONE);
        recyclerSearchResults.setAlpha(show?0.3f:1f);
    }

    // PillResult model for search
    private static class PillResult {
        int id;
        String externalCode;
        String name;
        String brand;
        String category;
        String form;
        String shape;
        String color;
        String imprint;
        String imageUrl;
    }

    // RecyclerView Adapter
    private static class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.ViewHolder> {
        private final List<PillResult> results;
        private final java.util.function.Consumer<PillResult> onClick;

        SearchResultAdapter(List<PillResult> results, java.util.function.Consumer<PillResult> onClick) {
            this.results = results;
            this.onClick = onClick;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PillResult p = results.get(position);
            String title = (p.category != null && !p.category.isEmpty()) ? p.category : (p.name != null ? p.name : "");
            holder.title.setText(title);
            holder.subtitle.setText(p.name != null ? p.name : "");
            if (p.imageUrl != null && !p.imageUrl.isEmpty()) {
                try { com.bumptech.glide.Glide.with(holder.itemView).load(p.imageUrl).into(holder.image); }
                catch (Throwable t) { holder.image.setImageResource(R.mipmap.ic_launcher); }
            } else {
                holder.image.setImageResource(R.mipmap.ic_launcher);
            }
            holder.itemView.setOnClickListener(v -> onClick.accept(p));
        }

        @Override
        public int getItemCount() {
            return results.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView title, subtitle;
            ImageView image;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.tv_search_result_title);
                subtitle = itemView.findViewById(R.id.tv_search_result_subtitle);
                image = itemView.findViewById(R.id.iv_search_result_image);
            }
        }
    }
}
