#!/bin/bash
# tools/gate.sh's groups, run one by one through tools/test_in_copy.sh from this checkout (Linux: its own copy dir).
# Stages 491 on run one per server: they were written and proven alone, and in one run they trip on each other's leftovers (docs/reports/watchdog-incidents.md).
ROOT="$1"; COPY="$2"; cd "$ROOT"
G=("0 86" "99 111" "117 120" "122 125" "180 183" "199 199" "200 206" "255 258" "276 285" "295 296" "297 297" "298 299" "300 301" "350 359" "360 363" "368 371" "375 375" "413 413" "415 415" "420 420" "430 431" "470 489")
for s in $(seq 491 574) 575 576 577 578 579 580 581 582 583 584 585 586 587 588 589 590 591 592 593 594 595 596 597 598 599 600 601 602 603 604 605 606 607 608 609 610 611 612 613 614 616 618 619 620 621 622 623 624 615 617 626 627 628 629 630 631 644 800 801 802 803 804 805 806 807 808 809 810 811 812 813 632 633 634 635 636 637 638 639 640 641 645 646 647 648 649 650 651 652 654 655 656 657 658 660 661 662 663 664 665 666 667 668 669 670 671 700 701 702 703 704 705 706 707 708 709 710 711 712 713 714 715 716 717 718 719 720; do G+=("$s $s"); done
for g in "${G[@]}"; do
  set -- $g
  out=$(timeout 900 env CLAUDE_TEST_COPY=$COPY JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dclaude.startStage=$1 -Dclaude.endStage=$2" bash tools/test_in_copy.sh 2>&1 | tail -3)
  echo "[$1-$2] ${out:-NO RESULT (timeout, crash or build failure)}"
done
