# Copy this file to run-remote-config.sh to set your local values.

# The user on the remote server, with server name, for SSH/SCP.
REMOTE_USER="ec2-user@..."

# Options for SSH/SCP calls from local machine.
SSH_OPTIONS="-i .../path/to/private/key.pem"

# Path to Java bin directory on remote server.
JAVA_BIN_DIR="jdk-12.0.1/bin"

# Should the script shutdown the remote server?
SHUTDOWN_AFTER_RUN="yes"

# An abitrary description to include in the generated report.
DESCRIPTION="Running on EC2..."
