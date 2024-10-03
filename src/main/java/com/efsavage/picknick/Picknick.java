package com.efsavage.picknick;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.scene.image.Image;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import com.drew.imaging.*;
import com.drew.metadata.*;
import com.drew.metadata.exif.*;

public class Picknick extends Application {

    private List<File> imageFiles = new ArrayList<>();
    private int currentIndex = 0;
    private ImageView imageView = new ImageView();
    private File tempImageFile;
    private Map<String, Image> preloadedImages = Collections.synchronizedMap(new HashMap<>());
    private Map<String, File> preloadedTempFiles = Collections.synchronizedMap(new HashMap<>());
    private Map<String, String> preloadedCaptureDates = Collections.synchronizedMap(new HashMap<>());
    private Stage primaryStage;
    private boolean isZoomedIn = false;
    private double zoomScale = 2.0; // Zoom scale factor

    private String dcrawPath = "dcraw"; // Assuming dcraw is on the PATH
    private String initialDirectoryPath = "x:/Dropbox/z8/import/pick";
    private File initialDirectory;
    private File keepDirectory;
    private File skipDirectory;
    private File maybeDirectory;
    private List<File> processedDirectories = new ArrayList<>();

    // Variables for dragging
    private double dragStartX;
    private double dragStartY;

    // Executor for preloading images
    private ExecutorService preloadExecutor = Executors.newFixedThreadPool(4);
    private static final int PRELOAD_COUNT = 10; // Number of images to preload ahead

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Picknick");

        BorderPane root = new BorderPane();

        // Create standard toolbar
        ToolBar toolBar = new ToolBar();

        Button keepButton = new Button("Keep (k)");
        keepButton.setOnAction(e -> keepImage());

        Button skipButton = new Button("Skip (s)");
        skipButton.setOnAction(e -> skipImage());

        Button maybeButton = new Button("Maybe (m)");
        maybeButton.setOnAction(e -> maybeImage());

        toolBar.getItems().addAll(keepButton, skipButton, maybeButton);

        root.setTop(toolBar);
        root.setCenter(imageView);
        BorderPane.setMargin(imageView, new Insets(10));

        // Load images from the initial directory
        selectInitialDirectory();
        processDirectory(initialDirectory);

        Scene scene = new Scene(root, 800, 600);

        // Keyboard shortcuts
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.K) {
                keepImage();
            } else if (event.getCode() == KeyCode.S) {
                skipImage();
            } else if (event.getCode() == KeyCode.M) {
                maybeImage();
            } else if (event.getCode() == KeyCode.F11) {
                toggleFullScreen();
            }
        });

        // Image double-click to zoom
        imageView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                toggleZoom(event);
            }
        });

        // Enable dragging when zoomed in
        imageView.setOnMousePressed(event -> {
            if (isZoomedIn) {
                dragStartX = event.getSceneX() - imageView.getTranslateX();
                dragStartY = event.getSceneY() - imageView.getTranslateY();
            }
        });

        imageView.setOnMouseDragged(event -> {
            if (isZoomedIn) {
                imageView.setTranslateX(event.getSceneX() - dragStartX);
                imageView.setTranslateY(event.getSceneY() - dragStartY);
            }
        });

        primaryStage.setScene(scene);

        // Bind imageView fitWidth and fitHeight to the root pane size minus margins
        DoubleBinding fitWidth = Bindings.createDoubleBinding(() ->
                        root.getWidth() - root.getPadding().getLeft() - root.getPadding().getRight(),
                root.widthProperty(), root.paddingProperty());

        DoubleBinding fitHeight = Bindings.createDoubleBinding(() ->
                        root.getHeight() - toolBar.getHeight() - root.getPadding().getTop() - root.getPadding().getBottom(),
                root.heightProperty(), toolBar.heightProperty(), root.paddingProperty());

        imageView.fitWidthProperty().bind(fitWidth);
        imageView.fitHeightProperty().bind(fitHeight);
        imageView.setPreserveRatio(true);

        primaryStage.show();
    }

    private void toggleFullScreen() {
        boolean isFullScreen = primaryStage.isFullScreen();
        primaryStage.setFullScreen(!isFullScreen);
        System.out.println("Fullscreen mode toggled to: " + (!isFullScreen));
    }

    private void selectInitialDirectory() {
        File defaultDirectory = new File(initialDirectoryPath);
        if (!defaultDirectory.exists()) {
            // Use default Windows Pictures folder
            defaultDirectory = new File(System.getProperty("user.home"), "Pictures");
            System.out.println("Default directory does not exist. Using Pictures folder: " + defaultDirectory.getAbsolutePath());
        } else {
            System.out.println("Using default initial directory: " + defaultDirectory.getAbsolutePath());
        }

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Image Directory");
        if (defaultDirectory.exists()) {
            directoryChooser.setInitialDirectory(defaultDirectory);
        }

        initialDirectory = directoryChooser.showDialog(primaryStage);

        if (initialDirectory == null || !initialDirectory.exists()) {
            showAlert("Directory Not Found", "No directory selected or directory does not exist.");
            System.out.println("No directory selected or directory does not exist.");
            System.exit(0);
        }

        System.out.println("Selected initial directory: " + initialDirectory.getAbsolutePath());

        keepDirectory = new File(initialDirectory, "keep");
        skipDirectory = new File(initialDirectory, "skip");
        maybeDirectory = new File(initialDirectory, "maybe");

        keepDirectory.mkdirs();
        skipDirectory.mkdirs();
        maybeDirectory.mkdirs();

        System.out.println("Keep directory: " + keepDirectory.getAbsolutePath());
        System.out.println("Skip directory: " + skipDirectory.getAbsolutePath());
        System.out.println("Maybe directory: " + maybeDirectory.getAbsolutePath());
    }

    private void processDirectory(File directory) {
        imageFiles.clear();
        currentIndex = 0;

        System.out.println("Processing directory: " + directory.getAbsolutePath());

        File[] files = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".nef"));
        if (files != null && files.length > 0) {
            Arrays.sort(files); // Sort files alphabetically
            for (File file : files) {
                imageFiles.add(file);
                System.out.println("Found image file: " + file.getName());
            }
            showImage();
        } else {
            // No images in this directory, proceed to next
            processedDirectories.add(directory);
            System.out.println("No NEF files found in directory: " + directory.getAbsolutePath());
            if (directory.equals(maybeDirectory)) {
                // All done
                cleanupEmptyDirectories();
                System.out.println("All images have been processed.");
                showAlert("Done", "All images have been processed.");
                openKeepDirectoryAndExit();
            } else {
                // If main directory is done, process 'maybe' directory
                processDirectory(maybeDirectory);
            }
        }
    }

    private void showImage() {
        if (currentIndex < imageFiles.size()) {
            File nefFile = imageFiles.get(currentIndex);
            String fileName = nefFile.getName();
            System.out.println("Displaying image: " + fileName);

            if (preloadedImages.containsKey(fileName)) {
                // Use preloaded image
                Image image = preloadedImages.get(fileName);
                imageView.setImage(image);
                tempImageFile = preloadedTempFiles.get(fileName);
                String captureDateTime = preloadedCaptureDates.get(fileName);
                System.out.println("Used preloaded image for: " + fileName);

                // Reset zoom state
                isZoomedIn = false;
                resetImageViewTransforms();

                // Update title with capture date and time
                if (captureDateTime != null) {
                    updateTitle(fileName + " - " + captureDateTime);
                } else {
                    updateTitle(fileName);
                }

                // Preload next images
                preloadNextImages();

            } else {
                // Load image in background thread
                Task<Void> loadImageTask = new Task<Void>() {
                    private Image image;
                    private String captureDateTime;
                    private File tempFile;

                    @Override
                    protected Void call() throws Exception {
                        tempFile = convertNEFToJPEG(nefFile);
                        image = new Image(tempFile.toURI().toString());
                        captureDateTime = getCaptureDateTime(nefFile);
                        return null;
                    }

                    @Override
                    protected void succeeded() {
                        super.succeeded();
                        // Check if the currentIndex hasn't changed
                        if (imageFiles.size() > currentIndex && imageFiles.get(currentIndex).equals(nefFile)) {
                            tempImageFile = tempFile;
                            imageView.setImage(image);

                            // Store in preloaded maps
                            preloadedImages.put(fileName, image);
                            preloadedTempFiles.put(fileName, tempFile);
                            preloadedCaptureDates.put(fileName, captureDateTime);

                            // Reset zoom state
                            isZoomedIn = false;
                            resetImageViewTransforms();

                            // Update title
                            if (captureDateTime != null) {
                                updateTitle(fileName + " - " + captureDateTime);
                            } else {
                                updateTitle(fileName);
                            }

                            // Preload next images
                            preloadNextImages();
                        } else {
                            // Image has changed; discard temp file
                            tempFile.delete();
                        }
                    }

                    @Override
                    protected void failed() {
                        super.failed();
                        // Handle failure
                        Throwable e = getException();
                        e.printStackTrace();
                        System.out.println("Error converting NEF to JPEG: " + fileName);
                        moveToDirectory(nefFile, skipDirectory);
                        deleteTempImageFile();
                        imageFiles.remove(currentIndex);
                        // Do not adjust currentIndex here
                        showImage();
                    }
                };

                // Start the task in a new thread
                new Thread(loadImageTask).start();
            }

        } else {
            // Proceed to next directory if any
            processedDirectories.add(initialDirectory);
            if (initialDirectory.equals(maybeDirectory)) {
                // All done
                cleanupEmptyDirectories();
                System.out.println("All images have been processed.");
                showAlert("Done", "All images have been processed.");
                openKeepDirectoryAndExit();
            } else {
                // Process 'maybe' directory
                processDirectory(maybeDirectory);
            }
        }
    }

    private void preloadNextImages() {
        // Remove preloaded images that are no longer needed
        Set<String> currentFileNames = new HashSet<>();
        for (File file : imageFiles) {
            currentFileNames.add(file.getName());
        }

        // Remove entries for files that are no longer in imageFiles
        preloadedImages.keySet().removeIf(fileName -> !currentFileNames.contains(fileName));
        preloadedTempFiles.keySet().removeIf(fileName -> {
            if (!currentFileNames.contains(fileName)) {
                File tempFile = preloadedTempFiles.get(fileName);
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                return true;
            }
            return false;
        });
        preloadedCaptureDates.keySet().removeIf(fileName -> !currentFileNames.contains(fileName));

        int maxIndex = Math.min(currentIndex + PRELOAD_COUNT, imageFiles.size());

        for (int index = currentIndex + 1; index < maxIndex; index++) {
            File nefFile = imageFiles.get(index);
            String fileName = nefFile.getName();
            if (!preloadedImages.containsKey(fileName)) {
                System.out.println("Preloading image: " + fileName);

                // Run preload task
                Task<Void> preloadTask = new Task<Void>() {
                    private Image image;
                    private File tempFile;
                    private String captureDateTime;

                    @Override
                    protected Void call() throws Exception {
                        tempFile = convertNEFToJPEG(nefFile);
                        image = new Image(tempFile.toURI().toString());
                        captureDateTime = getCaptureDateTime(nefFile);
                        return null;
                    }

                    @Override
                    protected void succeeded() {
                        super.succeeded();
                        // Check if the file is still in imageFiles
                        if (imageFiles.contains(nefFile)) {
                            preloadedImages.put(fileName, image);
                            preloadedTempFiles.put(fileName, tempFile);
                            preloadedCaptureDates.put(fileName, captureDateTime);
                            System.out.println("Preloaded image: " + fileName);
                        } else {
                            // Image has been removed; discard temp file
                            tempFile.delete();
                            System.out.println("Discarded preloaded image: " + fileName);
                        }
                    }

                    @Override
                    protected void failed() {
                        super.failed();
                        Throwable e = getException();
                        e.printStackTrace();
                        System.out.println("Error preloading NEF to JPEG: " + fileName);
                    }
                };

                preloadExecutor.submit(preloadTask);
            }
        }
    }

    private String getCaptureDateTime(File imageFile) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imageFile);

            // NEF files may store date in different directories
            ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            ExifSubIFDDirectory exifSubIFDDirectory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

            java.util.Date captureDate = null;

            if (exifSubIFDDirectory != null) {
                captureDate = exifSubIFDDirectory.getDateOriginal();
            }

            if (captureDate == null && exifIFD0Directory != null) {
                captureDate = exifIFD0Directory.getDate(ExifIFD0Directory.TAG_DATETIME);
            }

            if (captureDate != null) {
                System.out.println("Capture date: " + captureDate.toString());
                return captureDate.toString();
            } else {
                System.out.println("Capture date not found in metadata.");
            }
        } catch (ImageProcessingException | IOException e) {
            System.out.println("Failed to read metadata from: " + imageFile.getName());
            e.printStackTrace();
        }
        return null;
    }

    private void resetImageViewTransforms() {
        imageView.setTranslateX(0);
        imageView.setTranslateY(0);
        imageView.setScaleX(1);
        imageView.setScaleY(1);
    }

    private void toggleZoom(MouseEvent event) {
        if (isZoomedIn) {
            // Zoom out
            resetImageViewTransforms();
            isZoomedIn = false;
            System.out.println("Zoomed out");
        } else {
            // Zoom in
            // Get mouse position relative to imageView
            double mouseX = event.getX();
            double mouseY = event.getY();

            // Get image dimensions
            double imageWidth = imageView.getBoundsInLocal().getWidth();
            double imageHeight = imageView.getBoundsInLocal().getHeight();

            // Calculate the position of the mouse click relative to the image
            double relativeX = mouseX / imageWidth;
            double relativeY = mouseY / imageHeight;

            // Apply scaling
            imageView.setScaleX(zoomScale);
            imageView.setScaleY(zoomScale);

            // Calculate the new translation using the correct formula
            double newTranslateX = (0.5 - relativeX) * imageWidth;
            double newTranslateY = (0.5 - relativeY) * imageHeight;

            imageView.setTranslateX(newTranslateX);
            imageView.setTranslateY(newTranslateY);

            isZoomedIn = true;
            System.out.println("Zoomed in at position (" + mouseX + ", " + mouseY + ")");
        }
    }

    private File convertNEFToJPEG(File nefFile) throws IOException {
        // Create a temporary file for the JPEG image
        File jpegFile = File.createTempFile("temp_image", ".jpg");
        jpegFile.deleteOnExit();

        String[] command = {
                dcrawPath,
                "-e", // Extract embedded thumbnail
                "-c", // Write image data to standard output
                nefFile.getAbsolutePath()
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(jpegFile);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();

        System.out.println("Converting NEF to JPEG: " + nefFile.getName());

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("dcraw exited with code " + exitCode);
            }
            System.out.println("Conversion successful: " + jpegFile.getAbsolutePath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("dcraw process was interrupted", e);
        }

        return jpegFile;
    }

    private void keepImage() {
        File nefFile = imageFiles.get(currentIndex);
        String fileName = nefFile.getName();
        System.out.println("Keeping image: " + fileName);
        moveToDirectory(nefFile, keepDirectory);
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        removePreloadedImage(fileName);
        showImage();
    }

    private void skipImage() {
        File nefFile = imageFiles.get(currentIndex);
        String fileName = nefFile.getName();
        System.out.println("Skipping image: " + fileName);
        moveToDirectory(nefFile, skipDirectory);
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        removePreloadedImage(fileName);
        showImage();
    }

    private void maybeImage() {
        File nefFile = imageFiles.get(currentIndex);
        String fileName = nefFile.getName();
        System.out.println("Marking image as maybe: " + fileName);
        moveToDirectory(nefFile, maybeDirectory);
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        removePreloadedImage(fileName);
        showImage();
    }

    private void removePreloadedImage(String fileName) {
        preloadedImages.remove(fileName);
        preloadedCaptureDates.remove(fileName);
        File tempFile = preloadedTempFiles.remove(fileName);
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    private void moveToDirectory(File file, File targetDirectory) {
        try {
            Path targetPath = Paths.get(targetDirectory.getAbsolutePath(), file.getName());
            Files.move(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Moved " + file.getName() + " to " + targetDirectory.getName());
        } catch (IOException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to move file: " + file.getName());
            System.out.println("Failed to move file: " + file.getName());
        }
    }

    private void deleteTempImageFile() {
        if (tempImageFile != null && tempImageFile.exists()) {
            tempImageFile.delete();
            System.out.println("Deleted temporary image file: " + tempImageFile.getAbsolutePath());
        }
        tempImageFile = null;
    }

    private void updateTitle(String title) {
        Platform.runLater(() -> primaryStage.setTitle("Picknick - " + title));
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
            alert.setHeaderText(null);
            alert.setTitle(title);
            alert.showAndWait();
        });
    }

    private void cleanupEmptyDirectories() {
        deleteDirectoryIfEmpty(keepDirectory);
        deleteDirectoryIfEmpty(skipDirectory);
        deleteDirectoryIfEmpty(maybeDirectory);
        System.out.println("Cleaned up empty directories.");
    }

    private void deleteDirectoryIfEmpty(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files == null || files.length == 0) {
                boolean deleted = directory.delete();
                if (deleted) {
                    System.out.println("Deleted empty directory: " + directory.getAbsolutePath());
                } else {
                    System.out.println("Failed to delete directory: " + directory.getAbsolutePath());
                }
            }
        }
    }

    private void openKeepDirectoryAndExit() {
        if (Desktop.isDesktopSupported()) {
            try {
                System.out.println("Opening keep directory: " + keepDirectory.getAbsolutePath());
                Desktop.getDesktop().open(keepDirectory);
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("Failed to open keep directory.");
            }
        } else {
            System.out.println("Desktop is not supported. Cannot open keep directory.");
        }
        System.exit(0);
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        preloadExecutor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
