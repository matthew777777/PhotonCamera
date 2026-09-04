package com.particlesdevs.photoncamera.gallery.interfaces;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public interface OnItemInteractionListener{
    void onItemClicked(RecyclerView view, RecyclerView.ViewHolder holder, int position);
    void onLongItemClicked(RecyclerView view,RecyclerView.ViewHolder holder, int position);
    void onMultipleViewHoldersSelected(RecyclerView view, List<RecyclerView.ViewHolder> selection);
    void onViewHolderHovered(RecyclerView view, RecyclerView.ViewHolder holder);
}
