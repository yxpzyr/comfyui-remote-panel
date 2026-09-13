ComfyUI Remote Panel V1.4 patch

Fixes UI-workflow widget desynchronization seen in V1.3:
- connected widgets now still consume their widgets_values slot, preventing later values from shifting;
- partial input_order metadata no longer drops required inputs such as sampler_name / scheduler / custom combo fields;
- numeric inputs are validated against live /object_info min/max, so seed-sized values cannot be sent as cfg;
- keeps V1.3 normal UI JSON -> API conversion and V1.2 live combo/model-name repair.

Replace these files:
1. app/build.gradle
2. app/src/main/java/com/comfyremote/panel/MainActivity.java
3. app/src/main/java/com/comfyremote/panel/WorkflowUiConverter.java
