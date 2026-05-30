import { ref, watch, type Ref } from "vue";
import { dashboardApi } from "../../../app/api/client";
import type { AgentConfig, BoardOverview } from "../../../app/api/types";

const BOARD_RANGE_OPTIONS = [24, 72, 168];

export function useBoardOverview(config: Ref<AgentConfig>) {
  const board = ref<BoardOverview | null>(null);
  const boardLoading = ref(false);
  const boardError = ref("");
  const boardRangeHours = ref(BOARD_RANGE_OPTIONS[0]);

  watch(boardRangeHours, () => {
    void refreshBoard();
  });

  return {
    board,
    boardLoading,
    boardError,
    boardRangeHours,
    boardRangeOptions: BOARD_RANGE_OPTIONS,
    refreshBoard
  };

  async function refreshBoard() {
    boardLoading.value = true;
    boardError.value = "";
    try {
      board.value = await dashboardApi.board(config.value, {
        hours: boardRangeHours.value
      });
    } catch (ex) {
      boardError.value = ex instanceof Error ? ex.message : "无法读取看板数据";
    } finally {
      boardLoading.value = false;
    }
  }
}
