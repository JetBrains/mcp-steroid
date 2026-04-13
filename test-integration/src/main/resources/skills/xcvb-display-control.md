---
name: xcvb-display-control
description: Control the X11 display inside Docker containers - mouse, keyboard, clipboard, screenshots, and window management via xdotool and related tools.
---

# X11 Display Control Skill

Control the graphical display inside the Docker container. You have a full X11 desktop (Xvfb + fluxbox) at 3840x2160 resolution with IntelliJ IDEA running.

## Available Tools

All commands run directly in the container shell. Use your agent's shell execution capability.

### Mouse Control (xdotool)

```bash
# Move mouse to coordinates
xdotool mousemove --sync 960 540

# Left-click at position
xdotool mousemove --sync 960 540 && xdotool click 1

# Right-click at position
xdotool mousemove --sync 960 540 && xdotool click 3

# Double-click at position
xdotool mousemove --sync 960 540 && xdotool click --repeat 2 1

# Drag from (x1,y1) to (x2,y2)
xdotool mousemove --sync 100 200 mousedown 1 mousemove --sync 300 400 mouseup 1
```

### Keyboard Control (xdotool)

```bash
# Press a single key
xdotool key Return
xdotool key Tab
xdotool key Escape
xdotool key BackSpace
xdotool key Delete

# Key combinations
xdotool key ctrl+s          # Save
xdotool key ctrl+z          # Undo
xdotool key ctrl+shift+z    # Redo
xdotool key alt+F4          # Close window
xdotool key ctrl+shift+p    # Command palette
xdotool key ctrl+shift+a    # IntelliJ: Find Action
xdotool key alt+Return      # IntelliJ: Show intentions
xdotool key ctrl+shift+f    # IntelliJ: Find in files
xdotool key shift+F10       # IntelliJ: Run
xdotool key shift+F9        # IntelliJ: Debug

# Type text character by character (with inter-key delay)
xdotool type --delay 50 -- "Hello, World!"

# Type into a specific window
xdotool search --name "MyClass.kt" windowactivate && xdotool type --delay 50 -- "code here"
```

### Window Management (xdotool)

```bash
# Get active window ID
xdotool getactivewindow

# Find window by name and activate it
xdotool search --name "IntelliJ" windowactivate

# Find window by partial name
xdotool search --name "MyProject" windowactivate

# List all window IDs
xdotool search --name ""

# Get window name by ID
xdotool getwindowname <window_id>

# Resize/move window
xdotool windowsize <window_id> 1920 1080
xdotool windowmove <window_id> 0 0

# Minimize/maximize
xdotool windowminimize <window_id>
xdotool key super+Up  # maximize (fluxbox)
```

### Clipboard (xclip)

```bash
# Copy text to clipboard
echo -n "text to copy" | xclip -selection clipboard

# Read from clipboard
xclip -selection clipboard -o

# Copy file content to clipboard
cat /path/to/file | xclip -selection clipboard

# Paste: copy to clipboard then use Ctrl+V
echo -n "paste this" | xclip -selection clipboard && xdotool key ctrl+v
```

### Screenshots (scrot / import)

```bash
# Full screen screenshot
scrot /path/to/screenshot.png

# Capture a region (x, y, width, height)
import -window root -crop 800x600+0+0 /path/to/region.png

# Capture active window
import -window "$(xdotool getactivewindow)" /path/to/window.png

# Capture with delay (useful after triggering UI changes)
sleep 1 && scrot /path/to/screenshot.png
```

## Display Information

- **Resolution**: 3840x2160 (4K)
- **Display**: `:99` (set via DISPLAY environment variable)
- **Window Manager**: fluxbox
- **Color depth**: 24-bit

## Common Workflows

### Click a button in IntelliJ

1. Take a screenshot to see current state
2. Identify the button coordinates from the screenshot
3. Move mouse and click

```bash
scrot /tmp/current-state.png
# (analyze screenshot to find button position)
xdotool mousemove --sync 500 300 && xdotool click 1
```

### Open a file in IntelliJ editor

```bash
# Use Ctrl+Shift+N to open file by name
xdotool key ctrl+shift+n
sleep 0.5
xdotool type --delay 30 -- "MyClass.kt"
sleep 0.5
xdotool key Return
```

### Run a terminal command in IntelliJ's built-in terminal

```bash
# Open terminal: Alt+F12
xdotool key alt+F12
sleep 0.5
xdotool type --delay 30 -- "mvn test"
xdotool key Return
```

### Navigate to a line in the editor

```bash
# Ctrl+G to go to line
xdotool key ctrl+g
sleep 0.3
xdotool type --delay 30 -- "42"
xdotool key Return
```

## Tips

- Always use `--sync` with `mousemove` to wait for the cursor to arrive
- Add `sleep 0.3` to `sleep 1` between UI actions to let IntelliJ process events
- Take screenshots before and after actions to verify the UI state changed
- The display is 4K (3840x2160) - coordinates are in pixels at this resolution
- Use `xdotool search --name` to find windows by their title bar text
- IntelliJ keyboard shortcuts work when the IDE window is focused
