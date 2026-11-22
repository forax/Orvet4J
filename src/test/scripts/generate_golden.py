import pathlib
import subprocess
import sys

basics_dir = pathlib.Path(sys.argv[1])
python_exe = sys.executable  # the downloaded Python

timeout_count = 0
success_count = 0

for py_file in sorted(basics_dir.glob("*.py")):
    golden_file = py_file.with_suffix(".golden")

    try:
        result = subprocess.run(
            [python_exe, str(py_file)],
            capture_output=True,
            text=True,
            timeout=5
        )
    except subprocess.TimeoutExpired:
        print(f"TIMEOUT: {py_file}", file=sys.stderr)

        # Remove an old golden file to avoid keeping stale results.
        # golden_file.unlink(missing_ok=True)

        timeout_count += 1
        continue

    golden_file.write_text(result.stdout)
    print(f"Generated {golden_file}")
    success_count += 1

print(f"\nGenerated: {success_count}, Timed out: {timeout_count}")