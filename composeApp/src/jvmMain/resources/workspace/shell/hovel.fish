# Hovel workspace: fish's --init-command, which fish runs after the player's own config.
# Hovel never edits the player's files.

set -g __hovel_path (string split --no-empty : -- "$HOVEL_PATH_PREPEND")
set -e HOVEL_PATH_PREPEND

# Put Hovel's entries first, in order: now, and before every prompt.
function __hovel_prepend_path --on-event fish_prompt
    set -l rest
    for entry in $PATH
        contains -- $entry $__hovel_path; or set -a rest $entry
    end
    set -gx PATH $__hovel_path $rest
end
__hovel_prepend_path
