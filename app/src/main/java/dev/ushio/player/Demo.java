package dev.ushio.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class Demo {
    private static final String STORY = "终班列车亮起灯时，她在站台拾到了一封寄给未来的信。一段沿着海岸线展开的旅程，由此出发。";
    static final Title HERO = new Title("night", "星轨夜行", "TV", "", 0, 0, STORY, "2025", R.drawable.art_night, 0, 0);
    static final Title CITY = new Title("city", "余晖之城", "Movie", "", 0, 0,
            "潮汐退去之后，两个人在一座即将沉入夜色的城中寻找回去的路。", "2024", R.drawable.art_city, 0, 0);
    static final Title PORT = new Title("port", "蓝色港湾", "Movie", "", 0, 0,
            "一封迟到的明信片，把两个故人带回海边的夏天。", "2025", R.drawable.art_port, 0, 0);
    static final Title WOOD = new Title("wood", "林间回声", "Movie", "", 0, 0,
            "一段在森林中反复响起的录音，记录着一次无人记得的远足。", "2023", R.drawable.art_wood, 0, 0);

    static final List<Title> TITLES = Arrays.asList(HERO, CITY, PORT, WOOD);
    static final List<Title> EPISODES = Arrays.asList(
            episode(1, "最后一班车"), episode(2, "海面上的星光"), episode(3, "下一站黎明"));
    private static Title episode(int number, String name) {
        return new Title("night-" + number, name, "Episode", HERO.name, 1, number,
                STORY, "2025", HERO.image, 0, 0);
    }

    static List<DanmuClient.Comment> comments() {
        String[] text = {"第一排报到", "这片夜色好美", "非常适合戴耳机看", "我也想坐这一班车",
                "截屏当壁纸", "终于等到发车", "看见流星了吗", "好安静的夏夜", "打个卡 ★",
                "再看一遍", "天边的颜色太好看了", "旅途愉快", "我们下一站见", "这段值得收藏"};
        int[] colors = {0xffffff, 0xc7b8ff, 0xabf2ea, 0xf7d5a7};
        ArrayList<DanmuClient.Comment> result = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            result.add(new DanmuClient.Comment(450 + i * 455L, i == 10 || i == 42 ? 5 : 1,
                    colors[i % colors.length], text[(i * 5) % text.length]));
        }
        result.sort(Comparator.comparingLong(comment -> comment.time));
        return result;
    }
}
