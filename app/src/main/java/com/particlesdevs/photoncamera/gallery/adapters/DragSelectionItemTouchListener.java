package com.particlesdevs.photoncamera.gallery.adapters;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.gallery.interfaces.OnItemInteractionListener;

import java.util.ArrayList;
import java.util.List;

// Taken from DragSelectionItemTouchListener.java created by NikolaDespotoski

public class DragSelectionItemTouchListener extends LongPressItemTouchListener implements RecyclerView.OnItemTouchListener {
    private RecyclerView.ViewHolder mPreviousViewHolder;
    private final Rect mHitRect = new Rect();
    private final List<RecyclerView.ViewHolder> mRangeSelection = new ArrayList<>();


    public DragSelectionItemTouchListener(Context context, OnItemInteractionListener listener) {
        super(context, listener);
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_POINTER_UP) {
            cancelPreviousSelection();
            return false;
        } else {
            onLongPressedEvent(rv, e);
        }
        return mViewHolderLongPressed != null;
    }

    private void cancelPreviousSelection() {
        mViewHolderLongPressed = null;
        mViewHolderInFocus = null;
        mPreviousViewHolder = null;
        mRangeSelection.clear();
    }

    private boolean onActionMove(RecyclerView rv, MotionEvent e) {
        if (isMotionEventInCurrentViewHolder(e) || mViewHolderLongPressed == null) {
            return false;
        }
        if (mViewHolderLongPressed != null && mPreviousViewHolder == null) {
            mPreviousViewHolder = mViewHolderLongPressed;
        }
        View childViewUnder = rv.findChildViewUnder(e.getX(), e.getY());
        if (childViewUnder == null) return false;
        RecyclerView.ViewHolder viewHolder = rv.getChildViewHolder(childViewUnder);
        if (mPreviousViewHolder == null && viewHolder != null && mViewHolderLongPressed != null && viewHolder.getAbsoluteAdapterPosition() != mViewHolderLongPressed.getAbsoluteAdapterPosition()) {
            dispatchOnViewHolderHovered(rv, viewHolder);
            return true;
        } else if (mPreviousViewHolder != null && viewHolder != null && viewHolder.getAbsoluteAdapterPosition() != mPreviousViewHolder.getAbsoluteAdapterPosition()) {
            dispatchOnViewHolderHovered(rv, viewHolder);
            return true;
        }
        return false;
    }


    private boolean isMotionEventInCurrentViewHolder(MotionEvent e) {
        if (mPreviousViewHolder != null) {
            mPreviousViewHolder.itemView.getHitRect(mHitRect);
            return mHitRect.contains((int) e.getX(), (int) e.getY());
        }
        return false;
    }

    private void dispatchOnViewHolderHovered(RecyclerView rv, RecyclerView.ViewHolder viewHolder) {
        if (!checkForSpanSelection(rv, viewHolder)) {
            if (mListener != null) {
                mListener.onViewHolderHovered(rv, viewHolder);
            }
        }
        mPreviousViewHolder = viewHolder;
    }

    private boolean checkForSpanSelection(RecyclerView rv, RecyclerView.ViewHolder viewHolder) {
        if (rv.getLayoutManager() instanceof GridLayoutManager) {
            GridLayoutManager.LayoutParams endSelectionParams = (GridLayoutManager.LayoutParams) viewHolder.itemView.getLayoutParams();
            GridLayoutManager.LayoutParams startSelectionParams = (GridLayoutManager.LayoutParams) mPreviousViewHolder.itemView.getLayoutParams();
            if (endSelectionParams.getSpanIndex() != startSelectionParams.getSpanIndex()) {
                dispatchRangeSelection(rv, viewHolder);
                return true;
            }
        }
        return false;
    }

    private void dispatchRangeSelection(RecyclerView rv, RecyclerView.ViewHolder viewHolder) {
        if (mListener != null) {
            mRangeSelection.clear();
            int start = Math.min(mPreviousViewHolder.getAbsoluteAdapterPosition() + 1, viewHolder.getAbsoluteAdapterPosition());
            int end = Math.max(mPreviousViewHolder.getAbsoluteAdapterPosition() + 1, viewHolder.getAbsoluteAdapterPosition());
            for (int i = start; i <= end; i++) {
                mRangeSelection.add(rv.findViewHolderForAdapterPosition(i));
            }
            mListener.onMultipleViewHoldersSelected(rv, mRangeSelection);
        }
    }


    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_POINTER_UP) {
            cancelPreviousSelection();
        } else if (mViewHolderLongPressed != null) {
            onActionMove(rv, e);
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {

    }
}