
# Detector.kt Functions

### Core Detection Functions
- `detect(frame: Bitmap)`: Main detection function that processes images on a background thread
- `detectInternal()`: Core detection logic that:
   - Resizes input image
   - Processes image through TensorFlow
   - Finds bounding boxes
   - Draws detections if labels exist
- `bestBox()`: Processes model output to identify best object detections
- `applyNMS()`: Applies Non-Maximum Suppression to filter overlapping detections
- `calculateIOU()`: Calculates Intersection over Union between bounding boxes

### Setup and Helper Functions
- `setup()`: Initializes the TensorFlow Lite interpreter with the model and configures input/output tensors
- `setupCPUOnly()`: Fallback initialization without GPU acceleration
- `getOptimalInterpreterOptions()`: Configures TensorFlow Lite with optimal settings
- `loadLabels()`: Loads class labels from file
- `drawDetections()`: Draws bounding boxes and labels on detected objects
- `generateRandomColor()`: Creates consistent colors for object classes


# ImageDetector.kt (Image-specific Detection)

- `detectFromUri()`: Processes images from Android URI sources
- `detectFromPath()`: Processes images from file system paths


# VideoDetector.kt Functions

### Video Processing Functions
- `detectFromUri()`: Main entry point for processing video files
- `detectAndProcess()`: Processes individual video frames
- `saveProcessedVideoWithHardwareAcceleration()`: Saves processed frames as video
- `checkHardwareAccelerationSupport()`: Checks for hardware encoding support
- `parseProgress()`: Parses FFmpeg progress output

### Utility Functions
- `getVideoAspectRatio()`: Calculates video aspect ratio
- `stopProcessing()`: Cancels ongoing video processing
- `getMediaInfoExtractor()`: Returns media information extractor
- `getRealPathFromUri()`: Converts URI to file path



# ImageDetectorFragment.kt (UI Implementation)

- `setupDetector()`: Initializes the image detector with callbacks
- `setupUI()`: Sets up UI elements and button listeners
- `updateUIState()`: Updates UI based on processing state
- `showMessage()`: Displays toast messages to user
- `selectImage`: Activity result handler for image selection


# VideoDetectorFragment.kt Functions

### Core UI Functions
- `onViewCreated()`: Initializes the fragment's view, sets up detector and UI components
- `setupFpsSpinner()`: Creates and configures the FPS selection dropdown menu
- `setupVideoView(uri)`: Configures video playback view with proper aspect ratio and controls
- `setupPreviewOverlay()`: Creates an overlay ImageView for displaying processed frames
- `setupUI()`: Sets up click listeners for video selection and control buttons
- `updateControlsVisibility(visible)`: Shows/hides video playback controls
- `updatePlayPauseIcon(isPlaying)`: Updates play/pause button icon based on playback state

### Video Processing Functions
- `setupDetector()`: Initializes the ML detector with model and callback configuration
- `startProcessing()`: Begins video processing with selected FPS and shows progress
- `getVideoInfo(uri)`: Extracts video metadata (resolution, duration, frame rate)
- `updateVideoInfo()`: Updates UI with video metadata information
- `getRealPathFromUri()`: Converts content URI to file system path


# Key Features

1. **Hardware Acceleration**
   - Utilizes GPU and NNAPI when available
   - Falls back to CPU processing when needed

2. **Performance Optimizations**
   - Parallel frame processing
   - Hardware-accelerated video encoding/decoding
   - Frame dropping for performance

3. **UI Integration**
   - Progress reporting
   - Real-time preview
   - Interactive controls

4. **Error Handling**
   - Comprehensive error reporting
   - Resource cleanup

This implementation provides a complete pipeline for:
1. Video selection and preview
2. Frame extraction and processing
3. Object detection on frames
4. Result visualization
5. Video encoding and saving

### Notable Features
- Progress tracking and callback system
- Frame rate control and optimization
- Implements Non-Maximum Suppression for better detection
- Handles both real-time and static image detection
- Memory management for bitmap processing
- Integration with FFmpeg for video manipulation

The codebase implements a tricycle detection system using TensorFlow Lite for object detection, with support for both real-time camera feed and video file processing. It includes optimizations for hardware acceleration and handles various edge cases in video processing and UI interaction, with support for both static images and real-time detection.