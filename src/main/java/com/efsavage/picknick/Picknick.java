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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
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
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

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
    private ToolBar galleryToolBar;
    private VBox homeContent;
    private TilePane sessionTilePane;
    private ScrollPane sessionScrollPane;
    private TilePane galleryTilePane;
    private ScrollPane galleryScrollPane;
    private CheckBox showRejectedCheckBox;
    private Label homeSubtitle;
    private ProgressIndicator homeProgress;
    private DoubleBinding viewerFitWidth;
    private DoubleBinding viewerFitHeight;
    private CheckBox showArchivedCheckBox;

    private boolean isZoomedIn = false;
    private double zoomScale = 2.0;
    private double currentRotationAngle = 0.0;

    private final String dcrawPath = "dcraw";
    private final String rootDirectoryPath = "x:/Dropbox/picknick";
    private File rootDirectory;
    private File importDirectory;
    private File sessionDirectory;
    private File movDirectory;
    private File cacheDirectory;
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
    private Session gallerySession;
    private final Preferences preferences = Preferences.userNodeForPackage(Picknick.class);
    private static final String PREF_WINDOW_X = "window.x";
    private static final String PREF_WINDOW_Y = "window.y";
    private static final String PREF_WINDOW_W = "window.w";
    private static final String PREF_WINDOW_H = "window.h";
    private static final String PREF_WINDOW_MAX = "window.maximized";
    private static final String PREF_WINDOW_FS = "window.fullscreen";
    private static final String PREF_WINDOW_MIN = "window.iconified";
    private static final String SESSION_METADATA_FILE = "session.toml";

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Picknick");

        initRootDirectories();

        rootPane = new BorderPane();
        Scene scene = new Scene(rootPane, 900, 650);

        setupViewerToolBar();
        setupHomeToolBar();
        setupGalleryToolBar();
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
        cacheDirectory = new File(rootDirectory, ".cache");
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs();
        }
        if (!importDirectory.exists()) {
            importDirectory.mkdirs();
        }
        sessionDirectory.mkdirs();
        movDirectory.mkdirs();
        cacheDirectory.mkdirs();
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
        rescanButton.setOnAction(e -> refreshSessions(true));

        Button mergeButton = new Button("Merge Sessions");
        mergeButton.setOnAction(e -> showAlert("Merge Sessions", "Merge UI coming soon."));

        Button splitButton = new Button("Split Session");
        splitButton.setOnAction(e -> showAlert("Split Session", "Split UI coming soon."));

        showArchivedCheckBox = new CheckBox("Show archived");
        showArchivedCheckBox.setOnAction(e -> refreshSessions(false));

        homeToolBar = new ToolBar(rescanButton, mergeButton, splitButton, showArchivedCheckBox);
    }

    private void setupGalleryToolBar() {
        Button backButton = new Button("Back");
        backButton.setOnAction(e -> showHomeScreen());

        showRejectedCheckBox = new CheckBox("Show rejected");
        showRejectedCheckBox.setOnAction(e -> refreshGallery());

        galleryToolBar = new ToolBar(backButton, showRejectedCheckBox);
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
        gallerySession = null;
        updateTitle(null);

        if (homeContent == null) {
            homeContent = buildHomeContent();
        }
        rootPane.setTop(homeToolBar);
        rootPane.setCenter(homeContent);
        System.out.println("Home screen ready. Loading sessions...");
        refreshSessions(false);
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

    private void refreshSessions(boolean rescanImport) {
        System.out.println(rescanImport ? "Scanning import for sessions..." : "Loading sessions from disk...");
        setHomeBusy(true, rescanImport ? "Scanning import..." : "Loading sessions...");

        Task<List<Session>> scanTask = new Task<>() {
            @Override
            protected List<Session> call() {
                return loadSessions(rescanImport);
            }
        };

        scanTask.setOnSucceeded(event -> {
            List<Session> sessions = scanTask.getValue();
            System.out.println("Session scan complete. Found " + sessions.size() + " session(s).");
            sessionTilePane.getChildren().setAll(buildSessionCards(sessions));
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
        });

        scanTask.setOnFailed(event -> {
            Throwable error = scanTask.getException();
            if (error != null) {
                System.out.println("Session scan failed: " + error.getMessage());
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

        Label subtitle = new Label(formatSessionDateRange(session));
        subtitle.setStyle("-fx-text-fill: #666;");

        Label stats = new Label(formatSessionStats(session));
        stats.setStyle("-fx-text-fill: #666;");

        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(216);
        progressBar.setProgress(session.totalCount > 0
                ? (double) (session.totalCount - session.remainingCount) / session.totalCount
                : 0.0);

        Label percentLabel = new Label(formatSessionPercent(session));
        percentLabel.setStyle("-fx-text-fill: #444;");

        HBox progressRow = new HBox(8, progressBar, percentLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Button reviewButton = new Button("Review");
        reviewButton.setOnAction(event -> {
            startSession(session);
            event.consume();
        });

        Button viewButton = new Button("View");
        viewButton.setOnAction(event -> {
            showGallery(session);
            event.consume();
        });

        Button archiveButton = new Button(session.archived ? "Unarchive" : "Archive");
        archiveButton.setOnAction(event -> {
            toggleArchive(session);
            event.consume();
        });

        HBox actionRow = new HBox(8, reviewButton, viewButton, archiveButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox metaBox = new VBox(2, subtitle, stats);
        VBox card = new VBox(8, thumbnail, title, metaBox, progressRow, actionRow);
        card.setPadding(new Insets(12));
        card.setPrefWidth(240);
        card.setStyle("-fx-background-color: #f7f7f7; -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: #e0e0e0;");

        title.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                startRenameSession(card, title, session);
                event.consume();
            }
        });

        card.setOnDragDetected(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                var db = card.startDragAndDrop(TransferMode.MOVE);
                var content = new javafx.scene.input.ClipboardContent();
                if (session.directory != null) {
                    content.putString(session.directory.getAbsolutePath());
                    db.setContent(content);
                }
                event.consume();
            }
        });

        card.setOnDragOver(event -> {
            if (event.getGestureSource() != card && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        card.setOnDragDropped(event -> {
            var db = event.getDragboard();
            boolean success = false;
            if (db.hasString() && session.directory != null) {
                File sourceDir = new File(db.getString());
                if (!sourceDir.equals(session.directory)) {
                    runMergeAsync(sourceDir, session);
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });

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

    private void startRenameSession(VBox card, Label title, Session session) {
        if (session == null || session.directory == null) {
            return;
        }
        TextField editor = new TextField(session.displayName != null ? session.displayName : session.folderName);
        editor.setPrefWidth(title.getWidth() > 0 ? title.getWidth() : 200);

        int titleIndex = card.getChildren().indexOf(title);
        if (titleIndex < 0) {
            return;
        }
        card.getChildren().set(titleIndex, editor);
        editor.requestFocus();
        editor.selectAll();

        Runnable commit = () -> {
            String newName = editor.getText() != null ? editor.getText().trim() : "";
            if (newName.isBlank()) {
                newName = session.folderName;
            }
            SessionMetadata metadata = readSessionMetadata(session.directory);
            System.out.println("Renaming session " + session.folderName + " to \"" + newName + "\"");
            writeSessionMetadata(session.directory, newName, session.totalCount, metadata.archived);
            refreshSessions(false);
        };

        editor.setOnAction(e -> commit.run());
        editor.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commit.run();
            }
        });
    }

    private void mergeSessions(File sourceDir, Session targetSession) {
        mergeSessionsWithProgress(sourceDir, targetSession, null);
    }

    private void mergeSessionsWithProgress(File sourceDir, Session targetSession, ProgressReporter task) {
        if (sourceDir == null || targetSession == null || targetSession.directory == null) {
            return;
        }
        File targetDir = targetSession.directory;

        System.out.println("Merging session " + sourceDir.getName() + " into " + targetDir.getName());

        List<File> rootFiles = listMediaFiles(sourceDir);
        List<File> keepFiles = listMediaFiles(new File(sourceDir, "keep"));
        List<File> skipFiles = listMediaFiles(new File(sourceDir, "skip"));
        List<File> maybeFiles = listMediaFiles(new File(sourceDir, "maybe"));

        int total = rootFiles.size() + keepFiles.size() + skipFiles.size() + maybeFiles.size();
        if (task != null) {
            task.reportProgress(0, Math.max(1, total));
            task.reportMessage("Merging " + total + " files...");
        }

        int moved = 0;
        moved = moveFilesWithProgress(rootFiles, targetDir, task, moved, total, "Moving unreviewed");
        moved = moveFilesWithProgress(keepFiles, new File(targetDir, "keep"), task, moved, total, "Moving keep");
        moved = moveFilesWithProgress(skipFiles, new File(targetDir, "skip"), task, moved, total, "Moving skip");
        moved = moveFilesWithProgress(maybeFiles, new File(targetDir, "maybe"), task, moved, total, "Moving maybe");

        deleteSessionMetadataFile(sourceDir);
        pruneEmptyDirectories(sourceDir);
        deleteDirectoryIfEmpty(sourceDir);

        int totalCount = listMediaFiles(targetDir).size()
                + listMediaFiles(new File(targetDir, "keep")).size()
                + listMediaFiles(new File(targetDir, "skip")).size()
                + listMediaFiles(new File(targetDir, "maybe")).size();
        SessionMetadata metadata = readSessionMetadata(targetDir);
        writeSessionMetadata(targetDir, metadata.name, totalCount, metadata.archived);
        refreshSessions(false);
        System.out.println("Merge complete. New total: " + totalCount);
    }

    private void runMergeAsync(File sourceDir, Session targetSession) {
        MergeTask mergeTask = new MergeTask(sourceDir, targetSession);

        Stage dialog = showMergeDialog(mergeTask);
        mergeTask.setOnSucceeded(event -> {
            if (dialog != null) {
                dialog.close();
            }
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
        });
        mergeTask.setOnFailed(event -> {
            Throwable error = mergeTask.getException();
            if (error != null) {
                showAlert("Merge Failed", error.getMessage());
            }
            if (dialog != null) {
                dialog.close();
            }
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
        });

        new Thread(mergeTask, "session-merge").start();
    }

    private void runSplitAfterAsync(File pivotFile, Session session) {
        if (pivotFile == null || session == null || session.directory == null) {
            return;
        }
        setHomeBusy(true, "Splitting session...");
        SplitTask splitTask = new SplitTask(pivotFile, session);
        Stage dialog = showMergeDialog(splitTask);

        splitTask.setOnSucceeded(event -> {
            if (dialog != null) {
                dialog.close();
            }
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
            refreshSessions(false);
            refreshGallery();
        });

        splitTask.setOnFailed(event -> {
            Throwable error = splitTask.getException();
            if (error != null) {
                showAlert("Split Failed", error.getMessage());
            }
            if (dialog != null) {
                dialog.close();
            }
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
        });

        new Thread(splitTask, "session-split").start();
    }

    private void runSplitBeforeAsync(File pivotFile, Session session) {
        if (pivotFile == null || session == null || session.directory == null) {
            return;
        }
        setHomeBusy(true, "Splitting session...");
        SplitTask splitTask = new SplitTask(pivotFile, session, true);
        Stage dialog = showMergeDialog(splitTask);

        splitTask.setOnSucceeded(event -> {
            if (dialog != null) {
                dialog.close();
            }
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
            refreshSessions(false);
            refreshGallery();
        });

        splitTask.setOnFailed(event -> {
            Throwable error = splitTask.getException();
            if (error != null) {
                showAlert("Split Failed", error.getMessage());
            }
            if (dialog != null) {
                dialog.close();
            }
            setHomeBusy(false, "Import folder: " + importDirectory.getAbsolutePath());
        });

        new Thread(splitTask, "session-split-before").start();
    }

    private Stage showMergeDialog(Task<?> mergeTask) {
        if (primaryStage == null) {
            return null;
        }
        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(260);
        progressBar.progressProperty().bind(mergeTask.progressProperty());

        Label message = new Label();
        message.textProperty().bind(mergeTask.messageProperty());
        message.setStyle("-fx-font-size: 14px;");

        VBox content = new VBox(12, message, progressBar);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20));

        Scene scene = new Scene(content);
        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setResizable(false);
        dialog.setTitle("Picknick");
        dialog.setScene(scene);
        dialog.show();
        return dialog;
    }

    private void splitSessionAfterWithProgress(File pivotFile, Session session, ProgressReporter task) {
        splitSessionAroundWithProgress(pivotFile, session, task, false);
    }

    private void splitSessionBeforeWithProgress(File pivotFile, Session session, ProgressReporter task) {
        splitSessionAroundWithProgress(pivotFile, session, task, true);
    }

    private void splitSessionAroundWithProgress(File pivotFile, Session session, ProgressReporter task, boolean splitBefore) {
        Date pivotDate = getCaptureDate(pivotFile);
        if (pivotDate == null) {
            showAlert("Split Failed", "Could not read capture time for the selected image.");
            return;
        }

        File sessionDir = session.directory;
        List<File> rootFiles = listMediaFiles(sessionDir);
        List<File> keepFiles = listMediaFiles(new File(sessionDir, "keep"));
        List<File> skipFiles = listMediaFiles(new File(sessionDir, "skip"));
        List<File> maybeFiles = listMediaFiles(new File(sessionDir, "maybe"));

        List<File> rootMove = new ArrayList<>();
        List<File> keepMove = new ArrayList<>();
        List<File> skipMove = new ArrayList<>();
        List<File> maybeMove = new ArrayList<>();

        Date minDate = null;
        String pivotName = pivotFile.getName();
        minDate = collectSplitFiles(rootFiles, pivotDate, rootMove, minDate, splitBefore, pivotName);
        minDate = collectSplitFiles(keepFiles, pivotDate, keepMove, minDate, splitBefore, pivotName);
        minDate = collectSplitFiles(skipFiles, pivotDate, skipMove, minDate, splitBefore, pivotName);
        minDate = collectSplitFiles(maybeFiles, pivotDate, maybeMove, minDate, splitBefore, pivotName);

        int total = rootMove.size() + keepMove.size() + skipMove.size() + maybeMove.size();
        if (total == 0 || minDate == null) {
        if (task != null) {
            task.reportMessage(splitBefore ? "No images before this one." : "No images after this one.");
            task.reportProgress(1, 1);
        }
        return;
        }

        String dateKey = formatDateKey(minDate);
        int nextIndex = getNextSessionIndexByDate().getOrDefault(dateKey, 0) + 1;
        File newSessionDir = new File(sessionDirectory, dateKey + "-" + nextIndex);
        newSessionDir.mkdirs();
        File newKeepDir = new File(newSessionDir, "keep");
        File newSkipDir = new File(newSessionDir, "skip");
        File newMaybeDir = new File(newSessionDir, "maybe");
        newKeepDir.mkdirs();
        newSkipDir.mkdirs();
        newMaybeDir.mkdirs();

        if (task != null) {
            task.reportProgress(0, Math.max(1, total));
            task.reportMessage("Splitting " + total + " files...");
        }

        int moved = 0;
        moved = moveFilesWithProgress(rootMove, newSessionDir, task, moved, total, "Moving unreviewed");
        moved = moveFilesWithProgress(keepMove, newKeepDir, task, moved, total, "Moving keep");
        moved = moveFilesWithProgress(skipMove, newSkipDir, task, moved, total, "Moving skip");
        moved = moveFilesWithProgress(maybeMove, newMaybeDir, task, moved, total, "Moving maybe");

        writeSessionMetadata(newSessionDir, newSessionDir.getName(), total, false);

        int remainingTotal = listMediaFiles(sessionDir).size()
                + listMediaFiles(new File(sessionDir, "keep")).size()
                + listMediaFiles(new File(sessionDir, "skip")).size()
                + listMediaFiles(new File(sessionDir, "maybe")).size();
        SessionMetadata metadata = readSessionMetadata(sessionDir);
        writeSessionMetadata(sessionDir, metadata.name, remainingTotal, metadata.archived);
    }

    private Date collectSplitFiles(List<File> files, Date pivotDate, List<File> destination, Date minDate, boolean splitBefore, String pivotName) {
        for (File file : files) {
            Date captureDate = getCaptureDate(file);
            if (captureDate == null) {
                continue;
            }
            int cmp = compareByCaptureThenName(file, captureDate, new File(pivotName), pivotDate);
            boolean shouldMove = splitBefore ? cmp < 0 : cmp > 0;
            if (shouldMove) {
                destination.add(file);
                if (minDate == null || captureDate.before(minDate)) {
                    minDate = captureDate;
                }
            }
        }
        return minDate;
    }

    private void deleteDirectoryIfEmpty(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            boolean deleted = directory.delete();
            if (!deleted) {
                System.out.println("Failed to delete directory: " + directory.getAbsolutePath());
            }
        }
    }

    private void deleteSessionMetadataFile(File directory) {
        if (directory == null) {
            return;
        }
        File metadataFile = new File(directory, SESSION_METADATA_FILE);
        if (metadataFile.exists()) {
            try {
                Files.deleteIfExists(metadataFile.toPath());
            } catch (IOException e) {
                System.out.println("Failed to delete session metadata: " + metadataFile.getAbsolutePath());
            }
        }
    }

    private List<Session> loadSessions(boolean rescanImport) {
        if (rescanImport) {
            moveImportsToSessions();
        }
        return loadSessionsFromDisk(showArchivedCheckBox != null && showArchivedCheckBox.isSelected());
    }

    private void startSession(Session session) {
        if (session == null || session.files.isEmpty()) {
            showAlert("Empty Session", "No images found for this session.");
            return;
        }

        isViewerActive = true;
        currentSession = session;
        System.out.println("Starting session: " + session.folderName + " (" + session.remainingCount + " remaining)");
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

    private void showGallery(Session session) {
        if (session == null || session.directory == null) {
            return;
        }
        gallerySession = session;
        isViewerActive = false;
        System.out.println("Opening gallery for session: " + session.folderName);
        updateTitle("Gallery");

        if (galleryTilePane == null) {
            galleryTilePane = new TilePane();
            galleryTilePane.setHgap(16);
            galleryTilePane.setVgap(16);
            galleryTilePane.setPrefColumns(4);
            galleryTilePane.setPrefTileWidth(240);
            galleryTilePane.setPrefTileHeight(200);
            galleryTilePane.setTileAlignment(Pos.TOP_LEFT);
            galleryTilePane.setPadding(new Insets(8));

            galleryScrollPane = new ScrollPane(galleryTilePane);
            galleryScrollPane.setFitToWidth(true);
            galleryScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            galleryScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        }

        rootPane.setTop(galleryToolBar);
        rootPane.setCenter(galleryScrollPane);
        refreshGallery();
    }

    private void refreshGallery() {
        if (gallerySession == null || galleryTilePane == null) {
            return;
        }
        boolean showRejected = showRejectedCheckBox != null && showRejectedCheckBox.isSelected();
        System.out.println("Refreshing gallery. Show rejected: " + showRejected);
        List<GalleryItem> items = buildGalleryItems(gallerySession, showRejected);
        galleryTilePane.getChildren().setAll(buildGalleryCards(items));
    }

    private void showImage() {
        if (currentIndex < imageFiles.size()) {
            File nefFile = imageFiles.get(currentIndex);
            String fileKey = nefFile.getAbsolutePath();
            System.out.println("Displaying image: " + nefFile.getName());

            if (preloadedImages.containsKey(fileKey)) {
                Image image = preloadedImages.get(fileKey);
                imageView.setImage(image);
                File preloadedTemp = preloadedTempFiles.get(fileKey);
                tempImageFile = (preloadedTemp != null && !isCacheFile(preloadedTemp)) ? preloadedTemp : null;
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
                            tempImageFile = (tempFile != null && !isCacheFile(tempFile)) ? tempFile : null;
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
        System.out.println("Moving imports into sessions...");
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
                                System.out.println("Deleted NC_FLLST.DAT: " + path);
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
            System.out.println("Routing " + movFiles.size() + " MOV file(s) to top-level mov folder.");
            moveFiles(movFiles, movDirectory);
        }

        if (allFiles.isEmpty()) {
            System.out.println("No new media files in import.");
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

        known.sort((a, b) -> compareByCaptureThenName(a, captureDates.get(a), b, captureDates.get(b)));

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
            System.out.println("Creating session folder: " + sessionFolder.getName() + " (" + group.size() + " files)");
            moveFiles(group, sessionFolder);
            writeSessionMetadata(sessionFolder, dateKey + "-" + nextIndex, group.size());
        }

        if (!unknown.isEmpty()) {
            File unknownFolder = new File(sessionDirectory, "unknown");
            unknownFolder.mkdirs();
            System.out.println("Routing " + unknown.size() + " unknown-date files to session/unknown");
            moveFiles(unknown, unknownFolder);
            writeSessionMetadata(unknownFolder, "unknown", unknown.size());
        }

        pruneEmptyDirectories(importDirectory);
        System.out.println("Import processing complete.");
    }

    private List<GalleryItem> buildGalleryItems(Session session, boolean includeRejected) {
        List<GalleryItem> items = new ArrayList<>();
        if (session == null || session.directory == null) {
            return items;
        }

        items.addAll(buildGalleryItemsFromFolder(session.directory, MediaState.UNREVIEWED));
        items.addAll(buildGalleryItemsFromFolder(new File(session.directory, "keep"), MediaState.KEEP));
        items.addAll(buildGalleryItemsFromFolder(new File(session.directory, "maybe"), MediaState.MAYBE));

        if (includeRejected) {
            items.addAll(buildGalleryItemsFromFolder(new File(session.directory, "skip"), MediaState.SKIP));
        }

        items.sort((a, b) -> compareByCaptureThenName(a.file, getCaptureDate(a.file), b.file, getCaptureDate(b.file)));
        return items;
    }

    private List<GalleryItem> buildGalleryItemsFromFolder(File folder, MediaState state) {
        List<File> files = listMediaFiles(folder);
        List<GalleryItem> items = new ArrayList<>();
        for (File file : files) {
            items.add(new GalleryItem(file, state));
        }
        return items;
    }

    private List<javafx.scene.Node> buildGalleryCards(List<GalleryItem> items) {
        List<javafx.scene.Node> cards = new ArrayList<>();
        for (GalleryItem item : items) {
            cards.add(buildGalleryCard(item));
        }
        return cards;
    }

    private VBox buildGalleryCard(GalleryItem item) {
        ImageView thumbnail = new ImageView();
        thumbnail.setFitWidth(216);
        thumbnail.setFitHeight(144);
        thumbnail.setPreserveRatio(true);

        String borderColor = switch (item.state) {
            case KEEP -> "#3cb371";
            case MAYBE -> "#d9822b";
            case SKIP -> "#c0392b";
            default -> "#9e9e9e";
        };

        VBox card = new VBox(thumbnail);
        card.setPadding(new Insets(6));
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: " + borderColor + "; -fx-border-width: 2; -fx-border-radius: 6; -fx-background-radius: 6;");

        Image cached = sessionThumbnailCache.get(item.file.getAbsolutePath());
        if (cached != null) {
            thumbnail.setImage(cached);
        } else {
            thumbnailExecutor.submit(() -> {
                Image image = loadThumbnailForFile(item.file, 216);
                if (image != null) {
                    sessionThumbnailCache.put(item.file.getAbsolutePath(), image);
                    Platform.runLater(() -> thumbnail.setImage(image));
                }
            });
        }

        ContextMenu menu = new ContextMenu();
        MenuItem splitBefore = new MenuItem("Split before this");
        splitBefore.setOnAction(event -> runSplitBeforeAsync(item.file, gallerySession));
        MenuItem splitAfter = new MenuItem("Split after this");
        splitAfter.setOnAction(event -> runSplitAfterAsync(item.file, gallerySession));
        menu.getItems().addAll(splitBefore, splitAfter);
        card.setOnContextMenuRequested(event -> menu.show(card, event.getScreenX(), event.getScreenY()));

        return card;
    }

    private Image loadThumbnailForFile(File file, int targetWidth) {
        if (file == null) {
            return null;
        }
        try {
            File sourceFile = file;
            File tempFile = null;
            if (!isJpeg(file)) {
                tempFile = convertNEFToJPEG(file);
                sourceFile = tempFile;
            }
            return new Image(sourceFile.toURI().toString(), targetWidth, 0, true, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
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

    private List<Session> loadSessionsFromDisk(boolean includeArchived) {
        File[] folders = sessionDirectory.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) {
            return Collections.emptyList();
        }
        System.out.println("Loading sessions from disk. Include archived: " + includeArchived);

        List<Session> sessions = new ArrayList<>();

        for (File folder : folders) {
            SessionMetadata metadata = readSessionMetadata(folder);
            if (metadata.totalCount == 0) {
                metadata.totalCount = 0;
            }
            if (metadata.archived && !includeArchived) {
                continue;
            }

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
            session.displayName = metadata.name != null ? metadata.name : folder.getName();
            session.directory = folder;
            session.files.addAll(rootFiles);
            session.unknown = "unknown".equalsIgnoreCase(folder.getName());
            session.remainingCount = rootFiles.size();
            session.archived = metadata.archived;
            session.createdAt = metadata.createdAt;
            session.totalCount = totalCount;

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

    private int moveFilesWithProgress(List<File> files, File targetDirectory, ProgressReporter task, int moved, int total, String label) {
        if (files.isEmpty()) {
            return moved;
        }
        if (task != null) {
            task.reportMessage(label + " (" + moved + "/" + total + ")");
        }
        for (File file : files) {
            if (!moveToDirectory(file, targetDirectory)) {
                System.out.println("Skipping file (missing or failed move): " + file.getAbsolutePath());
            }
            moved++;
            if (task != null) {
                task.reportProgress(moved, Math.max(1, total));
                task.reportMessage(label + " (" + moved + "/" + total + ")");
            }
        }
        return moved;
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
        result.sort((a, b) -> compareByCaptureThenName(a, getCaptureDate(a), b, getCaptureDate(b)));
        return result;
    }

    private int compareByCaptureThenName(File a, Date aDate, File b, Date bDate) {
        if (aDate != null && bDate != null) {
            int cmp = aDate.compareTo(bDate);
            if (cmp != 0) {
                return cmp;
            }
        } else if (aDate == null && bDate != null) {
            return 1;
        } else if (aDate != null) {
            return -1;
        }
        String aName = a != null ? a.getName() : "";
        String bName = b != null ? b.getName() : "";
        return aName.compareToIgnoreCase(bName);
    }

    private void toggleArchive(Session session) {
        if (session == null || session.directory == null) {
            return;
        }
        SessionMetadata metadata = readSessionMetadata(session.directory);
        metadata.archived = !metadata.archived;
        System.out.println((metadata.archived ? "Archived" : "Unarchived") + " session: " + session.folderName);
        writeSessionMetadata(session.directory, metadata.name != null ? metadata.name : session.folderName, session.totalCount, metadata.archived);
        refreshSessions(false);
    }

    private SessionMetadata readSessionMetadata(File sessionFolder) {
        SessionMetadata metadata = new SessionMetadata();
        if (sessionFolder == null) {
            return metadata;
        }
        File metadataFile = new File(sessionFolder, SESSION_METADATA_FILE);
        if (!metadataFile.exists()) {
            metadata.name = sessionFolder.getName();
            metadata.createdAt = Instant.now().toString();
            return metadata;
        }

        try {
            TomlParseResult result = Toml.parse(metadataFile.toPath());
            metadata.name = result.getString("name");
            metadata.createdAt = result.getString("created_at");
            metadata.archived = Boolean.TRUE.equals(result.getBoolean("archived"));
        } catch (Exception e) {
            System.out.println("Failed to read session metadata: " + metadataFile.getAbsolutePath());
        }
        if (metadata.name == null) {
            metadata.name = sessionFolder.getName();
        }
        if (metadata.createdAt == null) {
            metadata.createdAt = Instant.now().toString();
        }
        return metadata;
    }

    private void writeSessionMetadata(File sessionFolder, String name, int totalCount) {
        writeSessionMetadata(sessionFolder, name, totalCount, null);
    }

    private void writeSessionMetadata(File sessionFolder, String name, int totalCount, Boolean archivedOverride) {
        if (sessionFolder == null) {
            return;
        }
        SessionMetadata existing = readSessionMetadata(sessionFolder);
        SessionMetadata metadata = new SessionMetadata();
        metadata.name = name != null ? name : existing.name;
        metadata.createdAt = existing.createdAt != null ? existing.createdAt : Instant.now().toString();
        metadata.archived = archivedOverride != null ? archivedOverride : existing.archived;
        metadata.totalCount = totalCount > 0 ? totalCount : existing.totalCount;

        File metadataFile = new File(sessionFolder, SESSION_METADATA_FILE);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(metadataFile.toPath()), StandardCharsets.UTF_8))) {
            writer.write("name = \"" + metadata.name + "\"");
            writer.newLine();
            writer.write("created_at = \"" + metadata.createdAt + "\"");
            writer.newLine();
            writer.write("archived = " + metadata.archived);
            writer.newLine();
            if (metadata.totalCount > 0) {
                writer.write("total_count = " + metadata.totalCount);
                writer.newLine();
            }
        } catch (IOException e) {
            System.out.println("Failed to write session metadata: " + metadataFile.getAbsolutePath());
        }
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

            if (captureDate != null && exifSubIFDDirectory != null) {
                String subSec = exifSubIFDDirectory.getString(ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL);
                if (subSec != null && !subSec.isBlank()) {
                    String digits = subSec.replaceAll("\\D", "");
                    if (!digits.isEmpty()) {
                        if (digits.length() > 3) {
                            digits = digits.substring(0, 3);
                        } else if (digits.length() < 3) {
                            digits = String.format("%-3s", digits).replace(' ', '0');
                        }
                        try {
                            int millis = Integer.parseInt(digits);
                            captureDate = new Date(captureDate.getTime() + millis);
                        } catch (NumberFormatException ignored) {
                            // Ignore malformed subseconds
                        }
                    }
                }
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
        return session.displayName != null ? session.displayName : session.folderName;
    }

    private String formatSessionStats(Session session) {
        if (session.totalCount > 0) {
            return session.remainingCount + " remaining of " + session.totalCount;
        }
        return session.files.size() + " remaining";
    }

    private String formatSessionPercent(Session session) {
        if (session.totalCount == 0) {
            return "0%";
        }
        int completed = session.totalCount - session.remainingCount;
        int percent = (int) Math.round((completed * 100.0) / session.totalCount);
        return percent + "%";
    }

    private String formatSessionDateRange(Session session) {
        if (session.start == null || session.end == null) {
            return "";
        }
        ZonedDateTime start = ZonedDateTime.ofInstant(session.start.toInstant(), ZoneId.systemDefault());
        ZonedDateTime end = ZonedDateTime.ofInstant(session.end.toInstant(), ZoneId.systemDefault());

        boolean includeYear = start.isBefore(ZonedDateTime.now().minusYears(1));
        boolean sameDay = start.toLocalDate().equals(end.toLocalDate());

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(includeYear ? "EEE MMM d, yyyy" : "EEE MMM d", Locale.ENGLISH);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(includeYear ? "EEE MMM d, yyyy h:mm a" : "EEE MMM d h:mm a", Locale.ENGLISH);

        if (sameDay) {
            return dateFormatter.format(start) + " " + timeFormatter.format(start) + " – " + timeFormatter.format(end);
        }

        return dateTimeFormatter.format(start) + " – " + dateTimeFormatter.format(end);
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
        File cached = getCachedJpeg(nefFile);
        if (cached != null) {
            return cached;
        }

        String cacheKey = computeCacheKey(nefFile);
        File cacheFile = new File(cacheDirectory, cacheKey + ".jpg");
        File tempFile = new File(cacheDirectory, cacheKey + ".tmp");

        String[] command = {
                dcrawPath,
                "-e",
                "-c",
                nefFile.getAbsolutePath()
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(tempFile);
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

        try {
            Files.move(tempFile.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempFile.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return cacheFile;
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

    private boolean isCacheFile(File file) {
        if (file == null || cacheDirectory == null) {
            return false;
        }
        try {
            return file.getCanonicalPath().startsWith(cacheDirectory.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    private File getCachedJpeg(File nefFile) {
        if (nefFile == null || cacheDirectory == null) {
            return null;
        }
        String cacheKey = computeCacheKey(nefFile);
        File cacheFile = new File(cacheDirectory, cacheKey + ".jpg");
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile;
        }
        return null;
    }

    private String computeCacheKey(File file) {
        String keySource = file.getAbsolutePath() + "|" + file.lastModified() + "|" + file.length();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(keySource.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(keySource.hashCode());
        }
    }

    private boolean moveToDirectory(File file, File targetDirectory) {
        try {
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                showAlert("Error", "Failed to create directory: " + targetDirectory.getAbsolutePath());
                return false;
            }
            Path targetPath = Paths.get(targetDirectory.getAbsolutePath(), file.getName());
            if (Files.exists(targetPath)) {
                if (resolveCollision(file.toPath(), targetPath)) {
                    System.out.println("Collision resolved (identical): " + targetPath.getFileName());
                    return true;
                }
                showAlert("Name Collision", "File already exists with different content: " + targetPath.getFileName());
                return false;
            }
            System.out.println("Moving file: " + file.getAbsolutePath() + " -> " + targetPath);
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
        System.out.println("Copying to temp: " + tempPath);
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

        System.out.println("Move verified: " + targetPath);
        Files.deleteIfExists(sourcePath);
        return true;
    }

    private boolean resolveCollision(Path sourcePath, Path targetPath) throws IOException {
        if (!Files.exists(sourcePath)) {
            return false;
        }
        long sourceSize = Files.size(sourcePath);
        long targetSize = Files.size(targetPath);
        if (sourceSize != targetSize) {
            return false;
        }
        byte[] sourceHash = computeHash(sourcePath);
        byte[] targetHash = computeHash(targetPath);
        if (MessageDigest.isEqual(sourceHash, targetHash)) {
            System.out.println("Identical collision; deleting source: " + sourcePath);
            Files.deleteIfExists(sourcePath);
            return true;
        }
        return false;
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
        private String displayName;
        private boolean unknown = false;
        private int totalCount;
        private int remainingCount;
        private boolean archived;
        private String createdAt;
    }

    private static class SessionMetadata {
        private String name;
        private String createdAt;
        private boolean archived;
        private int totalCount;
    }

    private interface ProgressReporter {
        void reportProgress(long workDone, long max);
        void reportMessage(String message);
    }

    private class MergeTask extends Task<Void> implements ProgressReporter {
        private final File sourceDir;
        private final Session targetSession;

        private MergeTask(File sourceDir, Session targetSession) {
            this.sourceDir = sourceDir;
            this.targetSession = targetSession;
        }

        @Override
        protected Void call() {
            mergeSessionsWithProgress(sourceDir, targetSession, this);
            return null;
        }

        @Override
        public void reportProgress(long workDone, long max) {
            updateProgress(workDone, max);
        }

        @Override
        public void reportMessage(String message) {
            updateMessage(message);
        }
    }

    private class SplitTask extends Task<Void> implements ProgressReporter {
        private final File pivotFile;
        private final Session session;
        private final boolean splitBefore;

        private SplitTask(File pivotFile, Session session) {
            this.pivotFile = pivotFile;
            this.session = session;
            this.splitBefore = false;
        }
        private SplitTask(File pivotFile, Session session, boolean splitBefore) {
            this.pivotFile = pivotFile;
            this.session = session;
            this.splitBefore = splitBefore;
        }

        @Override
        protected Void call() {
            if (splitBefore) {
                splitSessionBeforeWithProgress(pivotFile, session, this);
            } else {
                splitSessionAfterWithProgress(pivotFile, session, this);
            }
            return null;
        }

        @Override
        public void reportProgress(long workDone, long max) {
            updateProgress(workDone, max);
        }

        @Override
        public void reportMessage(String message) {
            updateMessage(message);
        }
    }

    private static class GalleryItem {
        private final File file;
        private final MediaState state;

        private GalleryItem(File file, MediaState state) {
            this.file = file;
            this.state = state;
        }
    }

    private enum MediaState {
        UNREVIEWED,
        KEEP,
        MAYBE,
        SKIP
    }

    public static void main(String[] args) {
        launch(args);
    }
}
