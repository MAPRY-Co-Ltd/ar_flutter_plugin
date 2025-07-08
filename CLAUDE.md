# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language
Conversations must be conducted in Japanese.

## Project Overview

This is `ar_flutter_plugin` - a Flutter plugin for creating collaborative Augmented Reality experiences that supports both ARKit (iOS) and ARCore (Android). The plugin provides a unified API for placing 3D objects, handling gestures, managing anchors, and implementing cloud-based shared AR experiences.

## Common Development Commands

### Flutter Commands
- `flutter pub get` - Install dependencies for the main plugin
- `flutter analyze` - Run static analysis on the plugin code
- `flutter test` - Run tests for the plugin
- `flutter format .` - Format Dart code

### Example App Commands
- `cd example && flutter pub get` - Install dependencies for the example app
- `cd example && flutter run` - Run the example app on connected device
- `cd example && flutter analyze` - Analyze example app code
- `cd example && flutter test` - Run example app tests

### Platform-Specific Commands
- `cd example/ios && pod install` - Install iOS dependencies
- `cd example/android && ./gradlew clean` - Clean Android build
- `cd example/android && ./gradlew assembleDebug` - Build Android debug APK

## Code Architecture

### Plugin Structure
The plugin follows Flutter's federated plugin architecture:
- **lib/**: Core Dart API exposed to Flutter apps
- **android/**: Android native implementation (Kotlin/Java)
- **ios/**: iOS native implementation (Swift/Objective-C)
- **example/**: Demo app showcasing all plugin features

### Core Components

#### Managers (lib/managers/)
- `ARSessionManager`: Handles AR session configuration, camera pose, and coordinate system
- `ARObjectManager`: Manages 3D objects/nodes, gesture handling, and transformations
- `ARAnchorManager`: Handles anchor creation, cloud anchor upload/download
- `ARLocationManager`: Manages location-based AR features

#### Models (lib/models/)
- `ARNode`: Represents 3D objects with position, rotation, scale
- `ARAnchor`: Represents spatial anchors (plane anchors, cloud anchors)
- `ARHitTestResult`: Results from ray casting against detected surfaces

#### Platform Views (lib/widgets/)
- `ARView`: Main widget that creates platform-specific AR views
- `PlatformARView`: Factory for creating iOS/Android-specific implementations
- `AndroidARView`: Android implementation using AndroidView
- `IosARView`: iOS implementation using UiKitView

### Native Implementation

#### Android (android/src/main/kotlin/)
- `ArFlutterPlugin.kt`: Main plugin class, method channel handler
- `AndroidARView.kt`: ARCore-based AR view implementation
- `ArModelBuilder.kt`: Loads and manages 3D models using Sceneform
- `CloudAnchorHandler.kt`: Google Cloud Anchor integration

#### iOS (ios/Classes/)
- `ArFlutterPlugin.m/.h`: Plugin registration and method channel setup
- `IosARView.swift`: ARKit-based AR view implementation
- `ArModelBuilder.swift`: Loads GLTF/GLB models using SceneKit
- `CloudAnchorHandler.swift`: Google Cloud Anchor integration
- `JWTGenerator.swift`: JWT token generation for cloud anchors

### Key Design Patterns

1. **Manager Pattern**: Core functionality is split across specialized managers
2. **Platform Channels**: Communication between Dart and native code via method channels
3. **Factory Pattern**: `PlatformARView` factory creates platform-specific implementations
4. **Callback Pattern**: Extensive use of callbacks for AR events (taps, gestures, anchor events)

## Development Notes

### Dependencies
- **Core**: ARCore 1.22.0+ (Android), ARKit (iOS 13.0+)
- **3D Models**: Sceneform (Android), SceneKit (iOS)
- **Cloud Features**: Google Cloud Anchor API, Firebase
- **Permissions**: Camera, location access required

### Testing
- Main plugin tests: `test/ar_flutter_plugin_test.dart`
- Example app tests: `example/test/widget_test.dart`
- Physical device required for AR testing (simulator/emulator limited)

### Examples Structure
The example app (`example/lib/examples/`) contains comprehensive demos:
- `debugoptionsexample.dart`: Visualize tracking data
- `localandwebobjectsexample.dart`: Local and remote 3D model loading
- `objectsonplanesexample.dart`: Plane detection and object placement
- `objectgesturesexample.dart`: Pan and rotation gestures
- `screenshotexample.dart`: AR view screenshot capture
- `cloudanchorexample.dart`: Cloud anchor sharing
- `externalmodelmanagementexample.dart`: External model database integration

### Cloud Anchor Setup
Complex setup required for cloud features (see `cloudAnchorSetup.md`):
- Google Cloud Console project with Cloud Anchor API
- Firebase project with Firestore
- OAuth 2.0 credentials for Android
- Service account for iOS
- Platform-specific configuration files

### Key File Locations
- Main plugin entry: `lib/ar_flutter_plugin.dart`
- Core AR view: `lib/widgets/ar_view.dart`
- Native Android: `android/src/main/kotlin/io/carius/lars/ar_flutter_plugin/`
- Native iOS: `ios/Classes/`
- Example app: `example/lib/main.dart`

## Platform-Specific Notes

### Android
- Minimum SDK: API 24 (Android 7.0)
- ARCore compatibility required
- Sceneform for 3D model rendering
- ProGuard rules needed for release builds

### iOS
- Minimum version: iOS 13.0
- ARKit framework required
- SceneKit for 3D model rendering
- Camera/location permissions in Info.plist

### Development Tips
- Use physical devices for AR testing
- Check ARCore/ARKit device compatibility
- Cloud anchor features require extensive setup
- Camera permissions essential for AR functionality
- Debug with `debug: true` in manager constructors