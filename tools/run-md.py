#!python3
import re
import subprocess
import sys

# Optional: Use 'rich' for beautifully formatted markdown in the terminal.
try:
    from rich.console import Console
    from rich.markdown import Markdown
    console = Console()
    USE_RICH = True
except ImportError:
    USE_RICH = False

def print_text(text):
    """Prints text, rendering it as Markdown if 'rich' is available."""
    text = text.strip()
    if not text:
        return
    
    if USE_RICH:
        console.print(Markdown(text))
    else:
        print(text)
        
    # Add a visual separator between text and code blocks
    print("\n" + "="*60 + "\n")

def process_markdown_file(file_path):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"Error: File '{file_path}' not found.")
        sys.exit(1)

    # Regex to find ```bash ... ``` blocks
    # re.DOTALL allows '.' to match newlines inside the code block
    pattern = re.compile(r'```bash\s*(.*?)\s*```', re.DOTALL)

    last_end = 0
    for match in pattern.finditer(content):
        # 1. Print the markdown text before this bash block
        text_part = content[last_end:match.start()]
        print_text(text_part)

        # 2. Extract and display the bash code
        bash_code = match.group(1).strip()
        if not bash_code:
            last_end = match.end()
            continue

        print(f"\033[1;36m[Bash Block]\033[0m")
        print(bash_code)
        print("-" * 60)

        # 3. Ask for permission
        try:
            choice = input("Execute this bash command? [y/N]: ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("\nAborted by user.")
            sys.exit(0)

        # 4. Execute or skip
        if choice == 'y':
            print("\033[1;32mExecuting...\033[0m\n")
            # Run the bash code in a subshell
            result = subprocess.run(['bash', '-c', bash_code])
            if result.returncode != 0:
                print(f"\n\033[1;31mCommand exited with error code {result.returncode}\033[0m")
        else:
            print("\033[1;33mSkipped.\033[0m")

        last_end = match.end()

    # 5. Print any remaining text after the last bash block
    remaining_text = content[last_end:]
    print_text(remaining_text)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python run_md.py <your_markdown_file.md>")
        sys.exit(1)
    
    process_markdown_file(sys.argv[1])