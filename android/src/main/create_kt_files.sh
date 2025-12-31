#!/bin/bash

# Function to create Kotlin files from Java files
create_kt_files() {
    local source_dir="$1"
    local target_dir="$2"

    # Find all Java files recursively
    find "$source_dir" -type f -name "*.java" | while read -r java_file; do
        # Get the relative path from source_dir
        local rel_path="${java_file#$source_dir/}"
        # Remove .java extension and add .kt
        local kt_file="${rel_path%.java}.kt"
        # Create the full target path
        local target_path="$target_dir/$kt_file"

        # Create the target directory if it doesn't exist
        mkdir -p "$(dirname "$target_path")"

        # Create the empty Kotlin file
        touch "$target_path"
        echo "Created: $target_path"
    done
}

# Check if source and target directories are provided
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <source_directory> <target_directory>"
    echo "Example: $0 android/src/main/java android/src/main/kotlin"
    exit 1
fi

# Get the absolute paths¬
source_dir="$(cd "$1" && pwd)"
target_dir="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"

# Create the target directory if it doesn't exist
mkdir -p "$target_dir"

# Create Kotlin files
create_kt_files "$source_dir" "$target_dir"

echo "Done! Created empty Kotlin files for all Java files."
