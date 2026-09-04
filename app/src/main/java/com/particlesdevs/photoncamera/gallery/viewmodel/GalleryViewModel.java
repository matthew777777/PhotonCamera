package com.particlesdevs.photoncamera.gallery.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GalleryViewModel extends AndroidViewModel {
    private final MutableLiveData<GalleryItem> allSelectedImagesFolder = new MutableLiveData<>(GalleryItem.createEmpty());
    private final MutableLiveData<List<GalleryItem>> selectedDisplayFolders = new MutableLiveData<>(new ArrayList<>(0));

    private final MutableLiveData<List<GalleryItem>> currentFolderImages = new MutableLiveData<>(new ArrayList<>(0));
    private final MutableLiveData<Boolean> updatePendingLiveData=new MutableLiveData<>(false);


    public MutableLiveData<Boolean> getUpdatePending() {
        return updatePendingLiveData;
    }

    public void setUpdatePending(boolean updatePending) {
        updatePendingLiveData.setValue(updatePending);
    }

    public GalleryViewModel(@NonNull Application application) {
        super(application);
    }

    public void fetchAllMedia() {
        List<GalleryItem> allFolders=GalleryFileOperations._fetchSelectedFolders(getApplication().getContentResolver()).stream().map((Function<GalleryFileOperations.ImagesFolder, GalleryItem>) imagesFolder -> {
            GalleryItem folder = new GalleryItem(imagesFolder.getTopImage());
            imagesFolder.getAllImageFiles().forEach(imageFile -> folder.getFiles().add(new GalleryItem(imageFile)));
            folder.setDisplayName(imagesFolder.getFolderName());
            return folder;
        }).collect(Collectors.toList());
        
        ArrayList<ImageFile> all = (ArrayList<ImageFile>) GalleryFileOperations.extractAllSelectedImages();
        if (!all.isEmpty()) {
            GalleryItem ALL_Folder = new GalleryItem(all.get(0));
            ALL_Folder.setDisplayName("ALL");
            ALL_Folder.getFiles().addAll(all.stream().map(GalleryItem::new).collect(Collectors.toList()));
            allSelectedImagesFolder.setValue(ALL_Folder);

            allFolders.add(0, ALL_Folder);
        }
        selectedDisplayFolders.setValue(allFolders);
    }

    public LiveData<GalleryItem> getAllSelectedImageFolder() {
        return allSelectedImagesFolder;
    }

    public MutableLiveData<List<GalleryItem>> getCurrentFolderImages() {
        return currentFolderImages;
    }

    public void setCurrentFolderImages(GalleryItem currentFolder) {
        currentFolderImages.setValue(currentFolder.getFiles());
    }

    public MutableLiveData<List<GalleryItem>> getSelectedDisplayFolders() {
        return selectedDisplayFolders;
    }
}
