import { FancyCardProps } from '@/types/home';
import renderIcon from '@/components/icon/renderIcon';

export default function FancyCard({ id, title, content }: Readonly<FancyCardProps>) {
  return (
    <div className='flex w-[300px] h-[100px] items-center p-4 rounded-lg shadow-sm border border-gray-200'>
      <div className='flex-1'>
        <p className='text-lg font-bold text-gray-900'>{title}</p>
        <p className='text-sm text-gray-500 whitespace-pre-line'>{content}</p>
      </div>
      {/* 아이콘 섹션 */}
      <div className='flex items-center justify-center w-12 h-12 bg-purple-100 rounded-full'>
        {renderIcon(id)}
      </div>
    </div>
  );
}
