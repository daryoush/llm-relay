#!/bin/bash

# Script: setup-git-key-enhanced.sh
# Usage: ./setup-git-key-enhanced.sh <project-name>

set -e

if [ $# -eq 0 ]; then
    echo "❌ Error: Project name is required"
    echo "Usage: ./setup-git-key-enhanced.sh <project-name>"
    exit 1
fi

PROJECT_NAME="$1"
SSH_DIR="$HOME/.ssh"
KEY_NAME="github_${PROJECT_NAME}"
KEY_PATH="${SSH_DIR}/${KEY_NAME}"
PUB_KEY_PATH="${KEY_PATH}.pub"

echo "🚀 Setting up Git SSH key for project: $PROJECT_NAME"
echo "================================================"

# Generate key (same as above)
mkdir -p "$SSH_DIR"
chmod 700 "$SSH_DIR"

echo "📝 Generating new SSH key..."
if [ -f "$KEY_PATH" ]; then
    echo "⚠️  Key already exists at $KEY_PATH"
    read -p "Do you want to overwrite it? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "❌ Cancelled by user"
        exit 1
    fi
    rm -f "$KEY_PATH" "$PUB_KEY_PATH"
fi

ssh-keygen -t ed25519 -C "deploy-key-${PROJECT_NAME}" -f "$KEY_PATH" -N ""

echo ""
echo "================================================"
echo "📋 YOUR PUBLIC KEY (copy this to GitHub):"
echo "================================================"
cat "$PUB_KEY_PATH"
echo ""
echo "================================================"
echo ""

# Add to SSH agent
eval "$(ssh-agent -s)" > /dev/null 2>&1
ssh-add "$KEY_PATH" 2>/dev/null

# Create a helper script for git commands
HELPER_SCRIPT="${SSH_DIR}/git-${PROJECT_NAME}.sh"
cat > "$HELPER_SCRIPT" << EOF
#!/bin/bash
# Helper script for git operations with ${PROJECT_NAME}
# Usage: ./$(basename "$HELPER_SCRIPT") push|pull|clone|status|...

GIT_SSH_COMMAND="ssh -i '$KEY_PATH' -o IdentitiesOnly=yes" git "\$@"
EOF

chmod +x "$HELPER_SCRIPT"

echo ""
echo "================================================"
echo "✅ Setup complete!"
echo "================================================"
echo ""
echo "📋 Your public key (copy this to GitHub):"
echo "   $(cat "$PUB_KEY_PATH")"
echo ""
echo "📂 Helper script created: $HELPER_SCRIPT"
echo ""
echo "🔧 Use the helper script for git operations:"
echo "   $HELPER_SCRIPT push"
echo "   $HELPER_SCRIPT pull"
echo "   $HELPER_SCRIPT clone git@github.com:username/repo.git"
echo ""
echo "📌 Or configure git to always use this key in this repo:"
echo "   cd /path/to/your/repo"
echo "   git config core.sshCommand \"ssh -i '$KEY_PATH' -o IdentitiesOnly=yes\""
echo ""
echo "🔍 Test connection:"
echo "   ssh -T -i '$KEY_PATH' -o IdentitiesOnly=yes git@github.com"
echo ""
echo "⚠️  Remember to add the public key as a DEPLOY KEY with WRITE ACCESS"
echo "   in your GitHub repository settings"
echo "================================================"