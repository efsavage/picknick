
# Picknick

Picknick is a simple JavaFX application designed to help you quickly sort through your Nikon NEF (RAW) image files. It allows you to preview images, zoom in for details, and categorize them into `keep`, `skip`, or `maybe` folders with easy keyboard shortcuts.

## Table of Contents

-   [Features](#features)
-   [Prerequisites](#prerequisites)
-   [Installation and Setup](#installation-and-setup)
-   [Running the Application](#running-the-application)
-   [Usage Instructions](#usage-instructions)
-   [Processing Flow](#processing-flow)
-   [Troubleshooting](#troubleshooting)

## Features

-   **Image Preview:** Quickly preview NEF images.
-   **Zooming and Panning:** Double-click to zoom in/out and drag to pan when zoomed.
-   **Easy Categorization:** Use keyboard shortcuts to move images to `keep`, `skip`, or `maybe` folders.
-   **Batch Preloading:** Preloads the next 10 images for faster browsing.
-   **Metadata Display:** Shows the capture date and time in the title bar.
-   **Resource Management:** Efficiently handles temporary files and memory usage.

## Prerequisites

Before running Picknick, ensure that you have the following installed on your system:

1.  **Java Development Kit (JDK) 8 or higher**

    -   Download and install from [Oracle's website](https://www.oracle.com/java/technologies/javase-downloads.html) or use OpenJDK from [AdoptOpenJDK](https://adoptopenjdk.net/).
2.  **dcraw**

    -   A command-line tool for decoding raw image data.
    -   Install via your package manager or download from dcraw's website.
3.  **metadata-extractor Library**

    -   A Java library for reading metadata from image files.
    -   Download the latest JAR file from [GitHub Releases](https://github.com/drewnoakes/metadata-extractor/releases).

## Installation and Setup

1.  **Clone or Download the Source Code**

    -   Clone the repository or download the source code to your local machine.
2.  **Place the `metadata-extractor` JAR File**

    -   Copy the `metadata-extractor-x.x.x.jar` file into the same directory as the `Picknick.java` file.
3.  **Ensure `dcraw` Is Accessible**

    -   Make sure `dcraw` is installed and accessible via your system's `PATH` environment variable.
4.  **Adjust the Initial Directory Path (Optional)**

    -   Open `Picknick.java` in a text editor.

    -   Locate the line:

        java

        Copy code

        `private String initialDirectoryPath = "x:/Dropbox/z8/import/pick";`

    -   Change the path to your preferred default directory or leave it as is to select a directory at runtime.


## Running the Application

Follow these steps to compile and run Picknick from the command line:

1.  **Open a Terminal or Command Prompt**

2.  **Navigate to the Source Code Directory**

    bash

    Copy code

    `cd path/to/your/source/code`

3.  **Compile the Application**

    bash

    Copy code

    `javac -cp .;metadata-extractor-x.x.x.jar Picknick.java`

    -   Replace `metadata-extractor-x.x.x.jar` with the actual filename of the JAR you downloaded.
    -   On Unix/Linux systems, replace `;` with `:` in the classpath.
4.  **Run the Application**

    bash

    Copy code

    `java -cp .;metadata-extractor-x.x.x.jar Picknick`

    -   Again, replace `;` with `:` on Unix/Linux systems.

## Usage Instructions

1.  **Select the Initial Directory**

    -   Upon launching, a directory chooser will appear.
    -   Navigate to the folder containing your NEF files and select it.
    -   The application will create `keep`, `skip`, and `maybe` subdirectories within this folder.
2.  **Keyboard Shortcuts**

    -   **`k`**: Keep the image (moves it to the `keep` directory).
    -   **`s`**: Skip the image (moves it to the `skip` directory).
    -   **`m`**: Mark as maybe (moves it to the `maybe` directory).
    -   **`F11`**: Toggle full-screen mode.
3.  **Toolbar Buttons**

    -   **Keep**: Click to keep the image.
    -   **Skip**: Click to skip the image.
    -   **Maybe**: Click to mark the image as maybe.
4.  **Zooming and Panning**

    -   **Double-click** on the image to zoom in by 200%.
    -   **Double-click** again to zoom out.
    -   When zoomed in, **click and drag** to pan around the image.
5.  **Title Bar Information**

    -   The application's title bar displays the filename and capture date/time if available, e.g.:

        yaml

        Copy code

        `Picknick - DSC_0001.NEF - Mon Sep 20 14:30:00 EDT 2023`


## Processing Flow

-   **Image Loading**

    -   The application processes images in the selected directory.
    -   It preloads the next 10 images in the background for faster viewing.
-   **Categorization**

    -   Use keyboard shortcuts or toolbar buttons to categorize images.
    -   Images are moved to the corresponding subdirectories.
-   **Automatic Progression**

    -   After processing all images in the main directory, the application automatically proceeds to the `maybe` directory if it exists.
-   **Completion**

    -   Once all images are processed, the application cleans up any temporary files and empty directories.
    -   A completion message is displayed.
    -   After dismissing the message, the `keep` directory is opened, and the application exits.

## Troubleshooting

-   **dcraw Not Found**

    -   Ensure that `dcraw` is installed and added to your system's `PATH`.
    -   Test by running `dcraw` in your terminal; it should display usage information.
-   **JavaFX Errors**

    -   Make sure you have JDK 8 or higher, which includes JavaFX.
    -   If using JDK 11 or higher, you may need to add JavaFX modules manually.
-   **Cannot Find `metadata-extractor`**

    -   Verify that the JAR file is in the same directory and the classpath is set correctly when compiling and running.
-   **Performance Issues**

    -   Preloading 10 images can be resource-intensive.
    -   Adjust the `PRELOAD_COUNT` constant in the code to a lower number if needed.