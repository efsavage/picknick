package com.efsavage.picknick;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToolBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;

public class Picknick extends Application {

    private static final long SESSION_GAP_MS = 60 * 60 * 1000L;

    private final List<File> imageFiles = new ArrayList<>();
    private int currentIndex = 0;
    private final ImageView imageView = new ImageView();
    private File tempImageFile;
    private final Map<String, Image> preloadedImages = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, File> preloadedTempFiles = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> preloadedCaptureDates = Collections.synchronizedMap(new HashMap<>());

    private Stage primaryStage;
    private BorderPane rootPane;
    private ToolBar viewerToolBar;
    private ToolBar homeToolBar;
    private VBox homeContent;
    private TilePane sessionTilePane;
    private ScrollPane sessionScrollPane;
    private Label homeSubtitle;
    private ProgressIndicator homeProgress;
    private DoubleBinding viewerFitWidth;
    private DoubleBinding viewerFitHeight;

    private boolean isZoomedIn = false;
    private double zoomScale = 2.0;
    private double currentRotationAngle = 0.0;

    private final String dcrawPath = "dcraw";
    private final String rootDirectoryPath = "x:/Dropbox/picknick";
    private File rootDirectory;
    private File importDirectory;
    private File sessionDirectory;
    private File movDirectory;
    private File sessionKeepDirectory;
    private File sessionSkipDirectory;
    private File sessionMaybeDirectory;

    private double dragStartX;
    private double dragStartY;

    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private static final int PRELOAD_COUNT = 10;

    private final Map<String, Image> sessionThumbnailCache = new ConcurrentHashMap<>();
    private final List<File> sessionThumbnailTempFiles = Collections.synchronizedList(new ArrayList<>());

    private boolean isViewerActive = false;
    private Session currentSession;
    private final Preferences preferences = Preferences.userNodeForPackage(Picknick.class);
    private static final String PREF_WINDOW_X = "window.x";
    private static final String PREF_WINDOW_Y = "window.y";
    private static final String PREF_WINDOW_W = "window.w";
    private static final String PREF_WINDOW_H = "window.h";
    private static final String PREF_WINDOW_MAX = "window.maximized";
    private static final String PREF_WINDOW_FS = "window.fullscreen";
    private static final String PREF_WINDOW_MIN = "window.iconified";

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Picknick");

        initRootDirectories();

        rootPane = new BorderPane();
        Scene scene = new Scene(rootPane, 900, 650);

        setupViewerToolBar();
        setupHomeToolBar();
        setupImageViewInteractions();
        setupSceneShortcuts(scene);
        setupViewerBindings();

        primaryStage.setScene(scene);
        restoreWindowState(primaryStage);
        showHomeScreen();
        primaryStage.show();
    }

    private void initRootDirectories() {
        rootDirectory = new File(rootDirectoryPath);
        importDirectory = new File(rootDirectory, "import");
        sessionDirectory = new File(rootDirectory, "session");
        movDirectory = new File(rootDirectory, "mov");
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs();
        }
        if (!importDirectory.exists()) {
            importDirectory.mkdirs();
        }
        sessionDirectory.mkdirs();
        movDirectory.mkdirs();
    }

    private void setupViewerToolBar() {
        Button homeButton = new Button("Sessions");
        homeButton.setOnAction(e -> showHomeScreen());

        Button keepButton = new Button("Keep (k)");
        keepButton.setOnAction(e -> keepImage());

        Button skipButton = new Button("Skip (s)");
        skipButton.setOnAction(e -> skipImage());

        Button maybeButton = new Button("Maybe (m)");
        maybeButton.setOnAction(e -> maybeImage());

        Button rotateClockwiseButton = new Button("Rotate Clockwise (r)");
        rotateClockwiseButton.setOnAction(e -> rotateClockwise());

        Button rotateCounterClockwiseButton = new Button("Rotate Counter-Clockwise (e)");
        rotateCounterClockwiseButton.setOnAction(e -> rotateCounterClockwise());

        viewerToolBar = new ToolBar(
                homeButton,
                keepButton,
                skipButton,
                maybeButton,
                rotateCounterClockwiseButton,
                rotateClockwiseButton
        );
    }

    private void setupHomeToolBar() {
        Button rescanButton = new Button("Rescan");
        rescanButton.setOnAction(e -> refreshSessions());

        Button mergeButton = new Button("Merge Sessions");
        mergeButton.setOnAction(e -> showAlert("Merge Sessions", "Merge UI coming soon."));

        Button splitButton = new Button("Split Session");
        splitButton.setOnAction(e -> showAlert("Split Session", "Split UI coming soon."));

        homeToolBar = new ToolBar(rescanButton, mergeButton, splitButton);
    }

    private void setupImageViewInteractions() {
        imageView.setPreserveRatio(true);

        imageView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                toggleZoom(event);
            }
        });

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
    }

    private void setupSceneShortcuts(Scene scene) {
        scene.setOnKeyPressed(event -> {
            if (!isViewerActive) {
                return;
            }
            if (event.getCode() == KeyCode.K) {
                keepImage();
            } else if (event.getCode() == KeyCode.S) {
                skipImage();
            } else if (event.getCode() == KeyCode.M) {
                maybeImage();
            } else if (event.getCode() == KeyCode.F11) {
                toggleFullScreen();
            } else if (event.getCode() == KeyCode.R) {
                rotateClockwise();
            } else if (event.getCode() == KeyCode.E) {
                rotateCounterClockwise();
            }
        });
    }

    private void setupViewerBindings() {
        viewerFitWidth = Bindings.createDoubleBinding(() ->
                        rootPane.getWidth() - rootPane.getPadding().getLeft() - rootPane.getPadding().getRight(),
                rootPane.widthProperty(), rootPane.paddingProperty());

        viewerFitHeight = Bindings.createDoubleBinding(() ->
                        rootPane.getHeight() - viewerToolBar.getHeight() - rootPane.getPadding().getTop() -
                                rootPane.getPadding().getBottom(),
                rootPane.heightProperty(), viewerToolBar.heightProperty(), rootPane.paddingProperty());

        imageView.fitWidthProperty().bind(viewerFitWidth);
        imageView.fitHeightProperty().bind(viewerFitHeight);
    }

    private void showHomeScreen() {
        isViewerActive = false;
        currentSession = null;
        updateTitle(null);

        if (homeContent == null) {
            homeContent = buildHomeContent();
        }
        rootPane.setTop(homeToolBar);
        rootPane.setCenter(homeContent);
        refreshSessions();
    }

    private VBox buildHomeContent() {
        Label title = new Label("Picknick Sessions");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        homeSubtitle = new Label(importDirectory.getAbsolutePath());
        homeSubtitle.setStyle("-fx-text-fill: #444;");

        homeProgress = new ProgressIndicator();
        homeProgress.setVisible(false);
        homeProgress.setPrefSize(18, 18);

        HBox headerRow = new HBox(10, title, homeProgress);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        sessionTilePane = new TilePane();
        sessionTilePane.setHgap(16);
        sessionTilePane.setVgap(16);
        sessionTilePane.setPrefColumns(3);
        sessionTilePane.setPrefTileWidth(240);
        sessionTilePane.setPrefTileHeight(260);
        sessionTilePane.setTileAlignment(Pos.TOP_LEFT);
        sessionTilePane.setPadding(new Insets(4));

        sessionScrollPane = new ScrollPane(sessionTilePane);
        sessionScrollPane.setFitToWidth(true);
        sessionScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sessionScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox container = new VBox(10, headerRow, homeSubtitle, sessionScrollPane);
        container.setPadding(new Insets(16));
        VBox.setVgrow(sessionScrollPane, Priority.ALWAYS);
        return container;
    }

    private void refreshSessions() {
        setHomeBusy(true, "Scanning import...");

        Task<List<Session>> scanTask = new Task<>() {
            @Override
            protected List<Session> call() {
                return rescanSessions();
            }
        };

        scanTask.setOnSucceeded(event -> {
            List<Session> sessions = scanTask.getValue();
            sessionTilePane.getChildren().setAll(buildSessionCards(sessions));
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
        });

        scanTask.setOnFailed(event -> {
            Throwable error = scanTask.getException();
            if (error != null) {
                showAlert("Scan Failed", error.getMessage());
            }
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
        });

        new Thread(scanTask).start();
    }

    private List<javafx.scene.Node> buildSessionCards(List<Session> sessions) {
        List<javafx.scene.Node> cards = new ArrayList<>();
        for (Session session : sessions) {
            cards.add(buildSessionCard(session));
        }
        return cards;
    }

    private VBox buildSessionCard(Session session) {
        ImageView thumbnail = new ImageView();
        thumbnail.setFitWidth(216);
        thumbnail.setFitHeight(132);
        thumbnail.setPreserveRatio(true);

        Label title = new Label(formatSessionTitle(session));
        title.setStyle("-fx-font-weight: bold;");

        Label subtitle = new Label(formatSessionSubtitle(session));
        subtitle.setStyle("-fx-text-fill: #666;");

        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(216);
        progressBar.setProgress(session.totalCount > 0
                ? (double) (session.totalCount - session.remainingCount) / session.totalCount
                : 0.0);

        Label percentLabel = new Label(formatSessionPercent(session));
        percentLabel.setStyle("-fx-text-fill: #444;");

        HBox progressRow = new HBox(8, progressBar, percentLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, thumbnail, title, subtitle, progressRow);
        card.setPadding(new Insets(12));
        card.setPrefWidth(240);
        card.setStyle("-fx-background-color: #f7f7f7; -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: #e0e0e0;");

        if (session.sampleFile != null) {
            String key = session.sampleFile.getAbsolutePath();
            Image cached = sessionThumbnailCache.get(key);
            if (cached != null) {
                thumbnail.setImage(cached);
            } else {
                thumbnailExecutor.submit(() -> {
                    Image image = loadSessionThumbnail(session);
                    if (image != null) {
                        Platform.runLater(() -> thumbnail.setImage(image));
                    }
                });
            }
        }

        card.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                startSession(session);
            }
        });

        return card;
    }

    private List<Session> rescanSessions() {
        moveImportsToSessions();
        return loadSessionsFromDisk();
    }

    private void startSession(Session session) {
        if (session == null || session.files.isEmpty()) {
            showAlert("Empty Session", "No images found for this session.");
            return;
        }

        isViewerActive = true;
        currentSession = session;
        initSessionDirectories(session);
        imageFiles.clear();
        imageFiles.addAll(session.files);
        currentIndex = 0;
        clearPreloadedImages();

        rootPane.setTop(viewerToolBar);
        rootPane.setCenter(imageView);
        BorderPane.setMargin(imageView, new Insets(10));

        showImage();
    }

    private void showImage() {
        if (currentIndex < imageFiles.size()) {
            File nefFile = imageFiles.get(currentIndex);
            String fileKey = nefFile.getAbsolutePath();
            System.out.println("Displaying image: " + nefFile.getName());

            if (preloadedImages.containsKey(fileKey)) {
                Image image = preloadedImages.get(fileKey);
                imageView.setImage(image);
                tempImageFile = preloadedTempFiles.get(fileKey);
                String captureDateTime = preloadedCaptureDates.get(fileKey);

                resetImageViewTransforms();

                if (captureDateTime != null) {
                    updateTitle(nefFile.getName() + " - " + captureDateTime);
                } else {
                    updateTitle(nefFile.getName());
                }

                preloadNextImages();

            } else {
                Task<Void> loadImageTask = new Task<Void>() {
                    private Image image;
                    private String captureDateTime;
                    private File tempFile;

                    @Override
                    protected Void call() throws Exception {
                        if (isJpeg(nefFile)) {
                            tempFile = null;
                            image = new Image(nefFile.toURI().toString());
                        } else {
                            tempFile = convertNEFToJPEG(nefFile);
                            image = new Image(tempFile.toURI().toString());
                        }
                        Date captureDate = getCaptureDate(nefFile);
                        captureDateTime = captureDate != null ? captureDate.toString() : null;
                        return null;
                    }

                    @Override
                    protected void succeeded() {
                        super.succeeded();
                        if (imageFiles.size() > currentIndex && imageFiles.get(currentIndex).equals(nefFile)) {
                            tempImageFile = tempFile;
                            imageView.setImage(image);

                            preloadedImages.put(fileKey, image);
                            preloadedTempFiles.put(fileKey, tempFile);
                            preloadedCaptureDates.put(fileKey, captureDateTime);

                            resetImageViewTransforms();

                            if (captureDateTime != null) {
                                updateTitle(nefFile.getName() + " - " + captureDateTime);
                            } else {
                                updateTitle(nefFile.getName());
                            }

                            preloadNextImages();
                        } else {
                            if (tempFile != null && tempFile.exists()) {
                                tempFile.delete();
                            }
                        }
                    }

                    @Override
                    protected void failed() {
                        super.failed();
                        Throwable e = getException();
                        e.printStackTrace();
                        System.out.println("Error converting NEF to JPEG: " + nefFile.getName());
                        if (sessionSkipDirectory != null) {
                            moveToDirectory(nefFile, sessionSkipDirectory);
                        }
                        deleteTempImageFile();
                        imageFiles.remove(currentIndex);
                        showImage();
                    }
                };

                new Thread(loadImageTask).start();
            }

        } else {
            onSessionComplete();
        }
    }

    private void onSessionComplete() {
        updateTitle(null);
        showAlert("Session Complete", "All images in this session have been processed.");
        showHomeScreen();
    }

    private void moveImportsToSessions() {
        List<File> allFiles = new ArrayList<>();
        List<File> movFiles = new ArrayList<>();
        try {
            Files.walk(importDirectory.toPath())
                    .filter(path -> Files.isRegularFile(path))
                    .forEach(path -> {
                        String filename = path.getFileName().toString().toLowerCase();
                        if (filename.equals("nc_fllst.dat")) {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                System.out.println("Failed to delete NC_FLLST.DAT: " + path);
                            }
                        } else if (filename.endsWith(".mov")) {
                            movFiles.add(path.toFile());
                        } else if (filename.endsWith(".nef") || filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                            allFiles.add(path.toFile());
                        }
                    });
        } catch (IOException e) {
            showAlert("Error", "Failed to scan import folder: " + e.getMessage());
        }

        if (!movFiles.isEmpty()) {
            moveFiles(movFiles, movDirectory);
        }

        if (allFiles.isEmpty()) {
            pruneEmptyDirectories(importDirectory);
            return;
        }

        List<File> known = new ArrayList<>();
        List<File> unknown = new ArrayList<>();
        Map<File, Date> captureDates = new HashMap<>();

        for (File file : allFiles) {
            Date captureDate = getCaptureDate(file);
            if (captureDate != null) {
                captureDates.put(file, captureDate);
                known.add(file);
            } else {
                unknown.add(file);
            }
        }

        known.sort(Comparator.comparing(captureDates::get));

        Map<String, Integer> nextIndexByDate = getNextSessionIndexByDate();
        List<List<File>> grouped = new ArrayList<>();
        List<File> currentGroup = new ArrayList<>();
        Date lastDate = null;

        for (File file : known) {
            Date captureDate = captureDates.get(file);
            if (lastDate == null || captureDate.getTime() - lastDate.getTime() > SESSION_GAP_MS) {
                if (!currentGroup.isEmpty()) {
                    grouped.add(currentGroup);
                }
                currentGroup = new ArrayList<>();
            }
            currentGroup.add(file);
            lastDate = captureDate;
        }
        if (!currentGroup.isEmpty()) {
            grouped.add(currentGroup);
        }

        for (List<File> group : grouped) {
            Date groupDate = captureDates.get(group.get(0));
            String dateKey = formatDateKey(groupDate);
            int nextIndex = nextIndexByDate.getOrDefault(dateKey, 0) + 1;
            nextIndexByDate.put(dateKey, nextIndex);

            File sessionFolder = new File(sessionDirectory, dateKey + "-" + nextIndex);
            sessionFolder.mkdirs();
            moveFiles(group, sessionFolder);
        }

        if (!unknown.isEmpty()) {
            File unknownFolder = new File(sessionDirectory, "unknown");
            unknownFolder.mkdirs();
            moveFiles(unknown, unknownFolder);
        }

        pruneEmptyDirectories(importDirectory);
    }

    private Map<String, Integer> getNextSessionIndexByDate() {
        Map<String, Integer> maxByDate = new HashMap<>();
        File[] folders = sessionDirectory.listFiles(File::isDirectory);
        if (folders == null) {
            return maxByDate;
        }

        for (File folder : folders) {
            String name = folder.getName();
            if ("unknown".equalsIgnoreCase(name)) {
                continue;
            }
            int dash = name.indexOf('-');
            if (dash <= 0 || dash >= name.length() - 1) {
                continue;
            }
            String dateKey = name.substring(0, dash);
            String indexPart = name.substring(dash + 1);
            if (!dateKey.matches("\\d{8}")) {
                continue;
            }
            try {
                int index = Integer.parseInt(indexPart);
                maxByDate.put(dateKey, Math.max(maxByDate.getOrDefault(dateKey, 0), index));
            } catch (NumberFormatException ignored) {
                // Skip malformed folder names
            }
        }

        return maxByDate;
    }

    private List<Session> loadSessionsFromDisk() {
        File[] folders = sessionDirectory.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) {
            return Collections.emptyList();
        }

        List<Session> sessions = new ArrayList<>();

        for (File folder : folders) {
            List<File> rootFiles = listMediaFiles(folder);
            File keepDir = new File(folder, "keep");
            File skipDir = new File(folder, "skip");
            File maybeDir = new File(folder, "maybe");

            List<File> keepFiles = listMediaFiles(keepDir);
            List<File> skipFiles = listMediaFiles(skipDir);
            List<File> maybeFiles = listMediaFiles(maybeDir);

            int totalCount = rootFiles.size() + keepFiles.size() + skipFiles.size() + maybeFiles.size();
            if (totalCount == 0) {
                continue;
            }

            Session session = new Session();
            session.folderName = folder.getName();
            session.directory = folder;
            session.files.addAll(rootFiles);
            session.unknown = "unknown".equalsIgnoreCase(folder.getName());
            session.totalCount = totalCount;
            session.remainingCount = rootFiles.size();

            if (!rootFiles.isEmpty()) {
                session.sampleFile = rootFiles.get(0);
            } else if (!keepFiles.isEmpty()) {
                session.sampleFile = keepFiles.get(0);
            } else if (!skipFiles.isEmpty()) {
                session.sampleFile = skipFiles.get(0);
            } else if (!maybeFiles.isEmpty()) {
                session.sampleFile = maybeFiles.get(0);
            }

            Date start = null;
            Date end = null;
            List<File> allFiles = new ArrayList<>();
            allFiles.addAll(rootFiles);
            allFiles.addAll(keepFiles);
            allFiles.addAll(skipFiles);
            allFiles.addAll(maybeFiles);

            for (File file : allFiles) {
                Date captureDate = getCaptureDate(file);
                if (captureDate == null) {
                    continue;
                }
                if (start == null || captureDate.before(start)) {
                    start = captureDate;
                }
                if (end == null || captureDate.after(end)) {
                    end = captureDate;
                }
            }

            session.start = start;
            session.end = end;
            sessions.add(session);
        }

        sessions.sort((a, b) -> {
            if (a.unknown && !b.unknown) {
                return 1;
            }
            if (!a.unknown && b.unknown) {
                return -1;
            }
            if (a.start == null && b.start == null) {
                return a.folderName.compareToIgnoreCase(b.folderName);
            }
            if (a.start == null) {
                return 1;
            }
            if (b.start == null) {
                return -1;
            }
            return b.start.compareTo(a.start);
        });

        return sessions;
    }

    private void moveFiles(List<File> files, File targetDirectory) {
        for (File file : files) {
            if (!moveToDirectory(file, targetDirectory)) {
                System.out.println("Skipping file (missing or failed move): " + file.getAbsolutePath());
            }
        }
    }

    private void initSessionDirectories(Session session) {
        if (session == null || session.directory == null) {
            sessionKeepDirectory = null;
            sessionSkipDirectory = null;
            sessionMaybeDirectory = null;
            return;
        }
        sessionKeepDirectory = new File(session.directory, "keep");
        sessionSkipDirectory = new File(session.directory, "skip");
        sessionMaybeDirectory = new File(session.directory, "maybe");
        sessionKeepDirectory.mkdirs();
        sessionSkipDirectory.mkdirs();
        sessionMaybeDirectory.mkdirs();
    }

    private void pruneEmptyDirectories(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        try {
            Files.walk(root.toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        if (Files.isDirectory(path)) {
                            try {
                                try (var stream = Files.list(path)) {
                                    if (!stream.findFirst().isPresent()) {
                                        if (!path.equals(root.toPath())) {
                                            Files.deleteIfExists(path);
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                // Ignore cleanup failures
                            }
                        }
                    });
        } catch (IOException e) {
            // Ignore cleanup failures
        }
    }

    private List<File> listMediaFiles(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = directory.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".nef") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>();
        Collections.addAll(result, files);
        return result;
    }

    private String formatDateKey(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ENGLISH);
        return format.format(date);
    }

    private void preloadNextImages() {
        preloadedImages.keySet().removeIf(key -> !imageFiles.contains(new File(key)));
        preloadedTempFiles.keySet().removeIf(key -> {
            if (!imageFiles.contains(new File(key))) {
                File tempFile = preloadedTempFiles.get(key);
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                return true;
            }
            return false;
        });
        preloadedCaptureDates.keySet().removeIf(key -> !imageFiles.contains(new File(key)));

        int maxIndex = Math.min(currentIndex + PRELOAD_COUNT, imageFiles.size());

        for (int index = currentIndex + 1; index < maxIndex; index++) {
            File nefFile = imageFiles.get(index);
            String fileKey = nefFile.getAbsolutePath();
            if (!preloadedImages.containsKey(fileKey)) {
                Task<Void> preloadTask = new Task<Void>() {
                    private Image image;
                    private File tempFile;
                    private String captureDateTime;

                    @Override
                    protected Void call() throws Exception {
                        if (isJpeg(nefFile)) {
                            tempFile = null;
                            image = new Image(nefFile.toURI().toString());
                        } else {
                            tempFile = convertNEFToJPEG(nefFile);
                            image = new Image(tempFile.toURI().toString());
                        }
                        Date captureDate = getCaptureDate(nefFile);
                        captureDateTime = captureDate != null ? captureDate.toString() : null;
                        return null;
                    }

                    @Override
                    protected void succeeded() {
                        super.succeeded();
                        if (imageFiles.contains(nefFile)) {
                            preloadedImages.put(fileKey, image);
                            preloadedTempFiles.put(fileKey, tempFile);
                            preloadedCaptureDates.put(fileKey, captureDateTime);
                        } else {
                            if (tempFile != null && tempFile.exists()) {
                                tempFile.delete();
                            }
                        }
                    }

                    @Override
                    protected void failed() {
                        super.failed();
                        Throwable e = getException();
                        e.printStackTrace();
                        System.out.println("Error preloading NEF to JPEG: " + nefFile.getName());
                    }
                };

                preloadExecutor.submit(preloadTask);
            }
        }
    }

    private Date getCaptureDate(File imageFile) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imageFile);

            ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            ExifSubIFDDirectory exifSubIFDDirectory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

            Date captureDate = null;

            if (exifSubIFDDirectory != null) {
                captureDate = exifSubIFDDirectory.getDateOriginal();
            }

            if (captureDate == null && exifIFD0Directory != null) {
                captureDate = exifIFD0Directory.getDate(ExifIFD0Directory.TAG_DATETIME);
            }

            return captureDate;
        } catch (ImageProcessingException | IOException e) {
            System.out.println("Failed to read metadata from: " + imageFile.getName());
            e.printStackTrace();
        }
        return null;
    }

    private String formatSessionTitle(Session session) {
        if (session.unknown) {
            return "Unknown Session";
        }
        if (session.start == null || session.end == null) {
            return session.folderName;
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE MMM d, yyyy h:mm a", Locale.ENGLISH);
        return dateFormat.format(session.start) + " - " + dateFormat.format(session.end);
    }

    private String formatSessionSubtitle(Session session) {
        String base;
        if (session.totalCount > 0) {
            base = session.remainingCount + " remaining of " + session.totalCount;
        } else {
            base = session.files.size() + " remaining";
        }
        if (session.folderName != null && !session.folderName.isBlank()) {
            return base + " • " + session.folderName;
        }
        return base;
    }

    private String formatSessionPercent(Session session) {
        if (session.totalCount == 0) {
            return "0%";
        }
        int completed = session.totalCount - session.remainingCount;
        int percent = (int) Math.round((completed * 100.0) / session.totalCount);
        return percent + "%";
    }

    private void resetImageViewTransforms() {
        imageView.setTranslateX(0);
        imageView.setTranslateY(0);
        imageView.setScaleX(1);
        imageView.setScaleY(1);
        imageView.setRotate(0);
        isZoomedIn = false;
        currentRotationAngle = 0.0;
    }

    private void toggleZoom(MouseEvent event) {
        if (isZoomedIn) {
            imageView.setScaleX(1);
            imageView.setScaleY(1);
            imageView.setTranslateX(0);
            imageView.setTranslateY(0);
            isZoomedIn = false;
        } else {
            double mouseX = event.getX();
            double mouseY = event.getY();

            double imageWidth = imageView.getBoundsInLocal().getWidth();
            double imageHeight = imageView.getBoundsInLocal().getHeight();

            double relativeX = mouseX / imageWidth;
            double relativeY = mouseY / imageHeight;

            imageView.setScaleX(zoomScale);
            imageView.setScaleY(zoomScale);

            double newTranslateX = (0.5 - relativeX) * imageWidth;
            double newTranslateY = (0.5 - relativeY) * imageHeight;

            imageView.setTranslateX(newTranslateX);
            imageView.setTranslateY(newTranslateY);

            isZoomedIn = true;
        }
    }

    private File convertNEFToJPEG(File nefFile) throws IOException {
        File jpegFile = File.createTempFile("temp_image", ".jpg");
        jpegFile.deleteOnExit();

        String[] command = {
                dcrawPath,
                "-e",
                "-c",
                nefFile.getAbsolutePath()
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(jpegFile);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("dcraw exited with code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("dcraw process was interrupted", e);
        }

        return jpegFile;
    }

    private void keepImage() {
        if (!isViewerActive || currentIndex >= imageFiles.size()) {
            return;
        }
        File nefFile = imageFiles.get(currentIndex);
        if (sessionKeepDirectory != null) {
            moveToDirectory(nefFile, sessionKeepDirectory);
        }
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        removePreloadedImage(nefFile.getAbsolutePath());
        showImage();
    }

    private void skipImage() {
        if (!isViewerActive || currentIndex >= imageFiles.size()) {
            return;
        }
        File nefFile = imageFiles.get(currentIndex);
        if (sessionSkipDirectory != null) {
            moveToDirectory(nefFile, sessionSkipDirectory);
        }
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        removePreloadedImage(nefFile.getAbsolutePath());
        showImage();
    }

    private void maybeImage() {
        if (!isViewerActive || currentIndex >= imageFiles.size()) {
            return;
        }
        File nefFile = imageFiles.get(currentIndex);
        if (sessionMaybeDirectory != null) {
            moveToDirectory(nefFile, sessionMaybeDirectory);
        }
        imageFiles.remove(currentIndex);
        deleteTempImageFile();
        removePreloadedImage(nefFile.getAbsolutePath());
        showImage();
    }

    private void removePreloadedImage(String fileKey) {
        preloadedImages.remove(fileKey);
        preloadedCaptureDates.remove(fileKey);
        File tempFile = preloadedTempFiles.remove(fileKey);
        if (tempFile != null && tempFile.exists()) {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void clearPreloadedImages() {
        for (File tempFile : preloadedTempFiles.values()) {
            if (tempFile != null && tempFile.exists()) {
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }
        preloadedImages.clear();
        preloadedTempFiles.clear();
        preloadedCaptureDates.clear();
    }

    private boolean isJpeg(File file) {
        if (file == null) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private boolean moveToDirectory(File file, File targetDirectory) {
        try {
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                showAlert("Error", "Failed to create directory: " + targetDirectory.getAbsolutePath());
                return false;
            }
            Path targetPath = Paths.get(targetDirectory.getAbsolutePath(), file.getName());
            if (Files.exists(targetPath)) {
                showAlert("Name Collision", "File already exists, skipping move: " + targetPath.getFileName());
                return false;
            }
            return safeMoveWithVerify(file.toPath(), targetPath);
        } catch (IOException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to move file: " + file.getName());
            return false;
        }
    }

    private boolean safeMoveWithVerify(Path sourcePath, Path targetPath) throws IOException {
        if (!Files.exists(sourcePath)) {
            return false;
        }
        long sourceSize = Files.size(sourcePath);
        byte[] sourceHash = computeHash(sourcePath);

        Path tempPath = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");
        Files.copy(sourcePath, tempPath, StandardCopyOption.REPLACE_EXISTING);

        long tempSize = Files.size(tempPath);
        byte[] tempHash = computeHash(tempPath);

        if (sourceSize != tempSize || !MessageDigest.isEqual(sourceHash, tempHash)) {
            Files.deleteIfExists(tempPath);
            throw new IOException("Verification failed for " + sourcePath.getFileName());
        }

        try {
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.deleteIfExists(sourcePath);
        return true;
    }

    private byte[] computeHash(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 not available", e);
        }

        try (DigestInputStream dis = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[8192];
            while (dis.read(buffer) != -1) {
                // DigestInputStream updates the digest automatically
            }
        }
        return digest.digest();
    }

    private void deleteTempImageFile() {
        if (tempImageFile != null && tempImageFile.exists()) {
            tempImageFile.delete();
        }
        tempImageFile = null;
    }

    private void updateTitle(String title) {
        Platform.runLater(() -> {
            if (title == null || title.isBlank()) {
                primaryStage.setTitle("Picknick");
            } else {
                primaryStage.setTitle("Picknick - " + title);
            }
        });
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
            alert.setHeaderText(null);
            alert.setTitle(title);
            alert.showAndWait();
        });
    }

    private void updateHomeSubtitle(String text) {
        if (homeSubtitle == null) {
            return;
        }
        Platform.runLater(() -> homeSubtitle.setText(text));
    }

    private void setHomeBusy(boolean busy, String subtitle) {
        if (homeProgress == null) {
            updateHomeSubtitle(subtitle);
            return;
        }
        Platform.runLater(() -> {
            homeProgress.setVisible(busy);
            if (sessionScrollPane != null) {
                sessionScrollPane.setDisable(busy);
            }
            homeToolBar.setDisable(busy);
            homeSubtitle.setText(subtitle);
        });
    }

    private void toggleFullScreen() {
        boolean isFullScreen = primaryStage.isFullScreen();
        primaryStage.setFullScreen(!isFullScreen);
    }

    private void restoreWindowState(Stage stage) {
        double x = preferences.getDouble(PREF_WINDOW_X, Double.NaN);
        double y = preferences.getDouble(PREF_WINDOW_Y, Double.NaN);
        double w = preferences.getDouble(PREF_WINDOW_W, 900);
        double h = preferences.getDouble(PREF_WINDOW_H, 650);
        boolean maximized = preferences.getBoolean(PREF_WINDOW_MAX, false);
        boolean fullscreen = preferences.getBoolean(PREF_WINDOW_FS, false);
        boolean iconified = preferences.getBoolean(PREF_WINDOW_MIN, false);

        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            stage.setX(x);
            stage.setY(y);
        }
        stage.setWidth(w);
        stage.setHeight(h);
        stage.setMaximized(maximized);
        stage.setFullScreen(fullscreen);
        stage.setIconified(false);
    }

    private void saveWindowState(Stage stage) {
        preferences.putDouble(PREF_WINDOW_X, stage.getX());
        preferences.putDouble(PREF_WINDOW_Y, stage.getY());
        preferences.putDouble(PREF_WINDOW_W, stage.getWidth());
        preferences.putDouble(PREF_WINDOW_H, stage.getHeight());
        preferences.putBoolean(PREF_WINDOW_MAX, stage.isMaximized());
        preferences.putBoolean(PREF_WINDOW_FS, stage.isFullScreen());
        preferences.putBoolean(PREF_WINDOW_MIN, stage.isIconified());
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        if (primaryStage != null) {
            saveWindowState(primaryStage);
        }
        preloadExecutor.shutdownNow();
        thumbnailExecutor.shutdownNow();
        for (File tempFile : sessionThumbnailTempFiles) {
            if (tempFile != null && tempFile.exists()) {
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }
    }

    private void rotateClockwise() {
        currentRotationAngle = (currentRotationAngle + 90) % 360;
        imageView.setRotate(currentRotationAngle);
    }

    private void rotateCounterClockwise() {
        currentRotationAngle = (currentRotationAngle - 90) % 360;
        imageView.setRotate(currentRotationAngle);
    }

    private Image loadSessionThumbnail(Session session) {
        if (session == null || session.sampleFile == null) {
            return null;
        }
        String key = session.sampleFile.getAbsolutePath();
        Image cached = sessionThumbnailCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            File sourceFile = session.sampleFile;
            File tempFile = null;
            if (!isJpeg(sourceFile)) {
                tempFile = convertNEFToJPEG(sourceFile);
                sessionThumbnailTempFiles.add(tempFile);
                sourceFile = tempFile;
            }
            Image image = new Image(sourceFile.toURI().toString(), 200, 0, true, true);
            sessionThumbnailCache.put(key, image);
            return image;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static class Session {
        private final List<File> files = new ArrayList<>();
        private Date start;
        private Date end;
        private File sampleFile;
        private File directory;
        private String folderName;
        private boolean unknown = false;
        private int totalCount;
        private int remainingCount;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
