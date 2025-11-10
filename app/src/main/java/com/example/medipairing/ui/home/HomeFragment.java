package com.example.medipairing.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.medipairing.R;
import com.example.medipairing.ui.auth.LoginFragment;
import com.example.medipairing.ui.medipairing.MedipairingFragment;
import com.example.medipairing.ui.mypage.MyPageFragment;
import com.example.medipairing.util.SessionManager;

public class HomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);
        bindEvents(root);
        return root;
    }

    private void bindEvents(View root) {
        View scanButton = root.findViewById(R.id.btn_scan_pill);
        View myPageButton = root.findViewById(R.id.btn_my_page);
        View logoutButton = root.findViewById(R.id.btn_logout);

        if (scanButton != null) {
            scanButton.setOnClickListener(v -> {
                if (getParentFragmentManager() != null) {
                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, new MedipairingFragment())
                            .addToBackStack(null)
                            .commit();
                }
            });
        }

        if (myPageButton != null) {
            myPageButton.setOnClickListener(v -> {
                if (getParentFragmentManager() != null) {
                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, new MyPageFragment())
                            .addToBackStack(null)
                            .commit();
                }
            });
        }

        if (logoutButton != null) {
            logoutButton.setOnClickListener(v -> logout());
        }
    }

    private void logout() {
        if (getContext() != null) {
            SessionManager.clearUser(getContext());
        }
        if (getParentFragmentManager() != null) {
            // Clear the back stack and navigate to LoginFragment
            getParentFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new LoginFragment())
                    .commit();
        }
    }
}
