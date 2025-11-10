package com.example.medipairing.ui.mypage;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medipairing.R;
import com.example.medipairing.model.Medicine;

import java.util.List;

public class MedicineAdapter extends RecyclerView.Adapter<MedicineAdapter.MedicineViewHolder> {

    private final List<Medicine> medicineList;
    private final OnMedicineClickListener listener;

    // Interface for click events
    public interface OnMedicineClickListener {
        void onMedicineClick(Medicine medicine);
    }

    // Constructor now accepts a listener
    public MedicineAdapter(List<Medicine> medicineList, OnMedicineClickListener listener) {
        this.medicineList = medicineList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public MedicineViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_medicine, parent, false);
        return new MedicineViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MedicineViewHolder holder, int position) {
        Medicine medicine = medicineList.get(position);
        holder.bind(medicine, listener); // Pass the listener to the ViewHolder
    }

    @Override
    public int getItemCount() {
        return medicineList.size();
    }

    public void removeItem(int position) {
        medicineList.remove(position);
        notifyItemRemoved(position);
    }

    // ViewHolder now binds the click listener
    public static class MedicineViewHolder extends RecyclerView.ViewHolder {
        ImageView medicineImage;
        TextView userDefinedName;
        TextView medicineInfo;

        public MedicineViewHolder(@NonNull View itemView) {
            super(itemView);
            medicineImage = itemView.findViewById(R.id.iv_medicine_image);
            userDefinedName = itemView.findViewById(R.id.tv_medicine_name_user);
            medicineInfo = itemView.findViewById(R.id.tv_medicine_info);
        }

        public void bind(final Medicine medicine, final OnMedicineClickListener listener) {
            // Title: user-defined alias first, then category, then actual name
            String title = medicine.getUserDefinedName();
            if (title == null || title.trim().isEmpty()) {
                title = medicine.getCategory();
                if (title == null || title.trim().isEmpty()) title = medicine.getActualName();
            }
            userDefinedName.setText(title != null ? title : "");

            // Subtitle: real pill name
            String sub = medicine.getActualName();
            medicineInfo.setText(sub != null ? sub : "");
            String url = medicine.getImageUrl();
            if (url != null && !url.isEmpty()) {
                try { com.bumptech.glide.Glide.with(itemView).load(url).into(medicineImage); } catch (Throwable t) { medicineImage.setImageResource(medicine.getImageResId()); }
            } else {
                medicineImage.setImageResource(medicine.getImageResId());
            }
            // Set the click listener on the entire item view
            itemView.setOnClickListener(v -> listener.onMedicineClick(medicine));
        }
    }
}
