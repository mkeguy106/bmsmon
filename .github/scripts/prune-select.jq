# prune-select.jq -- which TAGGED versions of the bmsmon-server package survive a prune.
# Pure: no network, no clock (the caller passes the cutoff). Unit-tested by test-prune-select.sh.
#
# Input : array of package versions {id, digest, created_at, tags}, any order.
# Args  : $keep     number  newest tagged versions retained unconditionally
#         $protect  array   image tags that must survive (deploy-tag commit shas, manual pins)
#         $cutoff   string  ISO-8601 UTC; tagged versions created at/after it are retained
# Output: {keep, delete, has_latest, unmatched_protect}. keep/delete partition the TAGGED
#         versions, newest first. Untagged versions are never selected here: prune-ghcr.sh
#         deletes those only once no surviving index references them.
def carries($tag): any(.tags[]; . == $tag);

([ .[] | select((.tags | length) > 0) ] | sort_by(.created_at) | reverse) as $tagged
| [ $tagged | to_entries[] | . as $e
    | select($e.key < $keep
             or ($e.value | carries("latest"))
             or $e.value.created_at >= $cutoff
             or any($protect[]; . as $p | $e.value | carries($p)))
    | $e.value ] as $kept
| ($kept | map(.id)) as $kept_ids
| { keep: $kept,
    delete: [ $tagged[] | select(.id as $i | any($kept_ids[]; . == $i) | not) ],
    has_latest: any($tagged[]; carries("latest")),
    unmatched_protect: [ $protect[] | select(. as $p | any($tagged[]; carries($p)) | not) ] }
