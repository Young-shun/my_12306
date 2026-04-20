package org.opengoofy.index60321;

import java.util.Scanner;
import java.util.concurrent.locks.ReentrantLock;

public class Main {
    // 5根叉子，用5把重入锁表示
    private final ReentrantLock[] forks = new ReentrantLock[5];

    public Main() {
        for (int i = 0; i < forks.length; i++) {
            forks[i] = new ReentrantLock();
        }
    }

    /**
     * 哲学家进餐的核心方法
     */
    public void wantsToEat(int philosopher,
            Runnable pickLeftFork,
            Runnable pickRightFork,
            Runnable eat,
            Runnable putLeftFork,
            Runnable putRightFork) {

        int leftForkId = philosopher;
        int rightForkId = (philosopher + 1) % 5;

        // 【防止死锁的关键】：资源排序逻辑
        // 永远先拿编号小的叉子，再拿编号大的叉子。
        // 这样 4 号哲学家会先尝试拿 0 号叉子（右），再拿 4 号叉子（左），
        // 从而破坏了循环等待环。
        int firstLock = Math.min(leftForkId, rightForkId);
        int secondLock = Math.max(leftForkId, rightForkId);

        forks[firstLock].lock();
        forks[secondLock].lock();

        try {
            pickLeftFork.run();
            pickRightFork.run();
            eat.run();
            putLeftFork.run();
            putRightFork.run();
        } finally {
            // 释放锁的顺序通常与加锁相反
            forks[secondLock].unlock();
            forks[firstLock].unlock();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Scanner sc = new Scanner(System.in);
        if (!sc.hasNextInt())
            return;
        int n = sc.nextInt(); // 每个哲学家需要进餐的次数

        Main solution = new Main();
        Thread[] philosophers = new Thread[5];

        for (int i = 0; i < 5; i++) {
            final int id = i;
            philosophers[i] = new Thread(() -> {
                for (int j = 0; j < n; j++) {
                    solution.wantsToEat(id,
                            () -> printAction(id, 1, 1), // 拿左叉
                            () -> printAction(id, 2, 1), // 拿右叉
                            () -> printAction(id, 0, 3), // 吃
                            () -> printAction(id, 1, 2), // 放左叉
                            () -> printAction(id, 2, 2) // 放右叉
                    );
                }
            });
        }

        // 启动所有线程
        for (Thread t : philosophers)
            t.start();

        // 等待所有线程完成
        for (Thread t : philosophers)
            t.join();
    }

    // 格式化输出：[哲学家编号, 资源类型, 动作]
    // 资源类型：1左叉，2右叉，0吃
    // 动作类型：1拿起，2放下，3吃
    private static void printAction(int philosopherId, int resource, int action) {
        System.out.println("[" + philosopherId + "," + resource + "," + action + "]");
    }
}