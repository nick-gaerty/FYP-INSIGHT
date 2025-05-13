# INSIGHT - Object Detection App for the Visually Impaired

This project is part of a Final Year Project (FYP) titled **INSIGHT: Intelligent Navigation System for Integrated Guidance Haptic Technologies**. It is a native Android application developed in Kotlin, designed to assist visually impaired individuals with indoor navigation through real-time object detection and multimodal feedback.

The app uses a TensorFlow Lite implementation of the YOLOv8 model to identify common indoor objects using the smartphone’s camera. Users receive feedback through **audio (Text-to-Speech)** in General Mode and **vibration cues** in Specific Mode, enabling non-visual spatial awareness and navigation.

---

## 📁 Project Structure

- **Main Kotlin Files**  
  Located in:  
  `app/src/main/java/com/surendramaran/yolov8tflite`  
  These files include the core application logic for object detection, mode switching, and feedback handling.

- **Model and Asset Files**  
  Located in:  
  `app/src/main/assets`  
  This folder includes the trained TensorFlow Lite model and label files used for inference.

---

