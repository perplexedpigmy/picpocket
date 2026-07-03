# ################################################################################
# Justfile for DocScanner Android Project
# 
# This file provides convenient tasks for common operations
# ################################################################################

SHELL := /usr/bin/env bash

# Application name
APP_NAME := docscanner

# Paths to the app directory
APP_DIR := app

# Default actions
.DEFAULT_GOAL := help

# Color output support
PRINT_SUCCESS = echo "\033[32m[OK]\033[0m"
PRINT_ERROR = echo "\033[31m[ERROR]\033[0m"
PRINT_INFO = echo "\033[34m[INFO]\033[0m"
PRINT_WARNING = echo "\033[33m[WARNING]\033[0m"

# Help target
help:
	@echo "Available commands:"
	@echo "  install         Install the app on an emulator or connected device"
	@echo "  install-debug   Install the debug APK"
	@echo "  install-device  Install the debug APK to a specific device"
	@echo "  uninstall       Uninstall the app from a device"
	@echo "  uninstall-device Uninstall from a specific device"
	@echo "  build           Build the app (assemble debug)"
	@echo "  test            Run unit tests"
	@echo "  connected-test  Run instrumentation tests"
	@echo "  lint            Run linting"
	@echo "  clean           Clean build artifacts"
	@echo ""
	@echo "Examples:"
	@echo "  just install    # Install to the first available device (emulator or USB)"
	@echo "  just uninstall # Uninstall from the connected device"

# Build the project
build:
	$(PRINT_INFO) "Building the project..."
	./gradlew assembleDebug
	$(PRINT_SUCCESS) "Build completed!"

# Install the debug APK to connected device or emulator
install:
	$(PRINT_INFO) "Installing app..."
	adb devices | grep -q "device$$" && \
	adb install -r app/build/outputs/apk/debug/app-debug.apk && \
	$(PRINT_SUCCESS) "App installed successfully!" || \
	adb devices | grep -q "emulator$$" && \
	adb install -r app/build/outputs/apk/debug/app-debug.apk && \
	$(PRINT_SUCCESS) "App installed on emulator!" || \
	$(PRINT_ERROR) "No device/emulator found!"

# Install to a specific device/emulator by ID
install-device:
	@if [ -z "$(DEVICE_ID)" ]; then \
		$(PRINT_ERROR) "Please specify a device ID: just install-device DEVICE_ID=<device-id>"; \
		exit 1; \
	fi
	$(PRINT_INFO) "Installing app to device $(DEVICE_ID)..."
	adb -s $(DEVICE_ID) install -r app/build/outputs/apk/debug/app-debug.apk
	$(PRINT_SUCCESS) "App installed on $(DEVICE_ID)!"

# Uninstall the app from all connected devices
uninstall:
	@adb devices | grep -q "device$$" && \
	(	adb uninstall $$(adb devices | grep "device$$" | cut -f1 | sed "s/ *$$//") && $(PRINT_SUCCESS) "App uninstall completed!" \
	) || \
	$(PRINT_WARNING) "No device connected!"

# Uninstall from a specific device
uninstall-device:
	@if [ -z "$(DEVICE_ID)" ]; then \
		$(PRINT_ERROR) "Please specify a device ID: just uninstall-device DEVICE_ID=<device-id>"; \
		exit 1; \
	fi
	$(PRINT_INFO) "Uninstalling app from device $(DEVICE_ID)..."
	yes | adb -s $(DEVICE_ID) uninstall $$(grep "package:" app/src/main/AndroidManifest.xml | cut -d"'" -f2 2>/dev/null) || \
	$(PRINT_WARNING) "Device $(DEVICE_ID) might not have the app installed"
	$(PRINT_SUCCESS) "App removed from $(DEVICE_ID)!")

# Run unit tests
.test:
	$(PRINT_INFO) "Running unit tests..."
	./gradlew test
	$(PRINT_SUCCESS) "Unit tests completed!"

# Run instrumentation tests
.connected-test:
	@find . -name "local.properties" | head -n1 >/dev/null || \
	($(PRINT_ERROR) "local.properties not found, please set SDK path"; exit 1)
	$(PRINT_INFO) "Running instrumentation tests..."
	./gradlew connectedDebugAndroidTest
	$(PRINT_SUCCESS) "Instrumentation tests completed!"

# Run linting
lint:
	./gradlew lint
	$(PRINT_SUCCESS) "Lint completed!"

# Clean build artifacts
clean:
	$(PRINT_INFO) "Cleaning build artifacts..."
	rm -rf app/build
	rm -rf .gradle
	rm -rf .project_cache
	$(PRINT_SUCCESS) "Build artifacts cleaned!"

.PHONY: help build install install-device uninstall uninstall-device test connected-test lint clean
