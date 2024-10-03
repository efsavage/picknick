package com.efsavage.picknick;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
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
import java.util.ArrayList;
import java.util.List;

public class Picknick extends Application {

    private List<File> imageFiles = new ArrayList<>();
    private int currentIndex = 0;
    private ImageView imageView = new ImageView();
    private File tempImageFile;
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

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Picknick");

        BorderPane root = new BorderPane();

        // ToolBar with buttons
        Button keepButton = new Button("Keep (k)");
        Button skipButton = new Button("Skip (s)");
        Button maybeButton = new Button("Maybe (m)");
        ToolBar toolBar = new ToolBar(keepButton, skipButton, maybeButton);

        root.setBottom(toolBar);
        root.setCenter(imageView);
        BorderPane.setMargin(imageView, new Insets(10));

        keepButton.setOnAction(e -> keepImage());
        skipButton.setOnAction(e -> skipImage());
        maybeButton.setOnAction(e -> maybeImage());

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
            System.out.println("Displaying image: " + nefFile.getName());
            try {
                tempImageFile = convertNEFToJPEG(nefFile);
                Image image = new Image(tempImageFile.toURI().toString());
                imageView.setImage(image);

                // Reset zoom state
                isZoomedIn = false;
                resetImageViewTransforms();

                updateTitle(nefFile.getName());
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("Error converting NEF to JPEG: " + nefFile.getName());
                moveToDirectory(nefFile, skipDirectory);
                deleteTempImageFile();
                imageFiles.remove(currentIndex);
                showImage();
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

            // Calculate the new translation using your fix
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
        System.out.println("Keeping image: " + nefFile.getName());
        moveToDirectory(nefFile, keepDirectory);
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        showImage();
    }

    private void skipImage() {
        File nefFile = imageFiles.get(currentIndex);
        System.out.println("Skipping image: " + nefFile.getName());
        moveToDirectory(nefFile, skipDirectory);
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        showImage();
    }

    private void maybeImage() {
        File nefFile = imageFiles.get(currentIndex);
        System.out.println("Marking image as maybe: " + nefFile.getName());
        moveToDirectory(nefFile, maybeDirectory);
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        showImage();
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
    }

    private void updateTitle(String title) {
        primaryStage.setTitle("Picknick - " + title);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText(null);
        alert.setTitle(title);
        alert.showAndWait();
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

    public static void main(String[] args) {
        launch(args);
    }
}
